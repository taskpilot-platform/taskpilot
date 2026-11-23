package com.taskpilot.projects.meetings.service;

import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reconciles in-flight LiveKit Egress recordings against LiveKit Cloud's ListEgress API
 * on application startup and periodically thereafter, recovering any meetings whose
 * {@code egress_ended} webhook was missed during a Hugging Face Space restart.
 */
@Slf4j
@Component
public class EgressReconciliationJob {

    private static final List<MeetingRecordingStatus> RECONCILABLE_STATUSES = List.of(
            MeetingRecordingStatus.STARTING,
            MeetingRecordingStatus.RECORDING,
            MeetingRecordingStatus.PROCESSING
    );

    private final ProjectMeetingRepository meetingRepository;
    private final LiveKitEgressClientAdapter egressClientAdapter;
    private final MeetingRecordingService meetingRecordingService;
    private final long missingTimeoutHours;
    private final long startingClaimTimeoutSeconds;

    public EgressReconciliationJob(
            ProjectMeetingRepository meetingRepository,
            LiveKitEgressClientAdapter egressClientAdapter,
            MeetingRecordingService meetingRecordingService) {
        this(meetingRepository, egressClientAdapter, meetingRecordingService, 24L, 120L);
    }

    public EgressReconciliationJob(
            ProjectMeetingRepository meetingRepository,
            LiveKitEgressClientAdapter egressClientAdapter,
            MeetingRecordingService meetingRecordingService,
            long missingTimeoutHours) {
        this(meetingRepository, egressClientAdapter, meetingRecordingService, missingTimeoutHours, 120L);
    }

    @Autowired
    public EgressReconciliationJob(
            ProjectMeetingRepository meetingRepository,
            LiveKitEgressClientAdapter egressClientAdapter,
            MeetingRecordingService meetingRecordingService,
            @Value("${egress.reconciliation.missing-timeout-hours:24}") long missingTimeoutHours,
            @Value("${egress.reconciliation.starting-claim-timeout-seconds:120}") long startingClaimTimeoutSeconds) {
        this.meetingRepository = meetingRepository;
        this.egressClientAdapter = egressClientAdapter;
        this.meetingRecordingService = meetingRecordingService;
        this.missingTimeoutHours = Math.max(1L, missingTimeoutHours);
        this.startingClaimTimeoutSeconds = Math.max(15L, startingClaimTimeoutSeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        try {
            reconcileInFlightRecordings();
        } catch (Exception e) {
            log.warn("Startup Egress reconciliation skipped/failed safely: {}", e.getMessage());
        }
    }

    @Scheduled(
            fixedDelayString = "${egress.reconciliation.interval-ms:120000}",
            initialDelayString = "${egress.reconciliation.initial-delay-ms:30000}"
    )
    public void reconcilePeriodically() {
        reconcileInFlightRecordings();
    }

    public int reconcileInFlightRecordings() {
        if (!egressClientAdapter.isConfigured()) {
            return 0;
        }

        List<ProjectMeetingEntity> stuckMeetings =
                meetingRepository.findByRecordingStatusInAndEgressIdIsNotNull(RECONCILABLE_STATUSES);

        int reconciled = 0;
        for (ProjectMeetingEntity meeting : stuckMeetings) {
            String egressId = meeting.getEgressId();
            if (egressId == null || egressId.isBlank()) {
                continue;
            }
            try {
                List<EgressSnapshot> snapshots = egressClientAdapter.listEgress(null, egressId);
                if (snapshots.isEmpty()) {
                    Instant lastTouched = meeting.getUpdatedAt() != null ? meeting.getUpdatedAt() : meeting.getCreatedAt();
                    Instant expireBefore = Instant.now().minus(Duration.ofHours(missingTimeoutHours));
                    if (lastTouched != null && lastTouched.isBefore(expireBefore)) {
                        log.warn("Egress {} for meeting {} not found in LiveKit after {}h; marking recording FAILED",
                                egressId, meeting.getId(), missingTimeoutHours);
                        meetingRecordingService.markMissingEgressFailed(
                                meeting.getId(),
                                egressId,
                                "LiveKit Egress record " + egressId + " was no longer found after " + missingTimeoutHours + " hours"
                        );
                        reconciled++;
                    } else {
                        log.warn("Reconciliation found no LiveKit Egress record for meetingId={}, egressId={}",
                                meeting.getId(), egressId);
                    }
                    continue;
                }
                EgressSnapshot latest = snapshots.get(0);
                meetingRecordingService.applyEgressSnapshot(latest);
                if (!latest.isTerminal()
                        && (meeting.getStatus() == ProjectMeetingStatus.ENDING
                            || meeting.getStatus() == ProjectMeetingStatus.ENDED)) {
                    meetingRecordingService.stopRecordingIfActive(meeting);
                }
                reconciled++;
            } catch (Exception e) {
                log.warn("Failed to reconcile Egress {} for meeting {}: {}",
                        egressId, meeting.getId(), e.getMessage());
            }
        }

        // Recover meetings that crashed mid-RPC after claimRecordingStart (STARTING with egress_id IS NULL)
        Instant staleClaimCutoff = Instant.now().minusSeconds(startingClaimTimeoutSeconds);
        List<ProjectMeetingEntity> orphanedClaims = meetingRepository
                .findByRecordingStatusAndEgressIdIsNullAndUpdatedAtBefore(
                        MeetingRecordingStatus.STARTING, staleClaimCutoff);
        for (ProjectMeetingEntity orphan : orphanedClaims) {
            try {
                List<EgressSnapshot> byRoom = orphan.getRoomName() != null && !orphan.getRoomName().isBlank()
                        ? egressClientAdapter.listEgress(orphan.getRoomName(), null)
                        : List.of();
                if (!byRoom.isEmpty()) {
                    EgressSnapshot recovered = pickBestRoomSnapshot(byRoom);
                    log.info("Recovered orphaned STARTING claim for meeting {} by attaching LiveKit Egress {}",
                            orphan.getId(), recovered.egressId());
                    meetingRecordingService.recoverStartingClaim(orphan.getId(), recovered);
                } else {
                    log.warn("Releasing stale STARTING claim (egress_id=null) for meeting {} after {}s",
                            orphan.getId(), startingClaimTimeoutSeconds);
                    meetingRecordingService.releaseStaleStartingClaim(
                            orphan.getId(),
                            "Cloud recording start did not complete before server restart; please try recording again"
                    );
                }
                reconciled++;
            } catch (Exception e) {
                log.warn("Failed to recover stale STARTING claim for meeting {}: {}", orphan.getId(), e.getMessage());
            }
        }

        return reconciled;
    }

    private static EgressSnapshot pickBestRoomSnapshot(List<EgressSnapshot> snapshots) {
        return snapshots.stream()
                .filter(s -> "EGRESS_ACTIVE".equals(s.status())
                        || "EGRESS_STARTING".equals(s.status())
                        || "EGRESS_ENDING".equals(s.status()))
                .findFirst()
                .orElse(snapshots.get(snapshots.size() - 1));
    }
}
