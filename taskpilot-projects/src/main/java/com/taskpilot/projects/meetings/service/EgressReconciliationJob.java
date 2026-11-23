package com.taskpilot.projects.meetings.service;

import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reconciles in-flight LiveKit Egress recordings against LiveKit Cloud's ListEgress API
 * on application startup and periodically thereafter, recovering any meetings whose
 * {@code egress_ended} webhook was missed during a Hugging Face Space restart.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgressReconciliationJob {

    private static final List<MeetingRecordingStatus> RECONCILABLE_STATUSES = List.of(
            MeetingRecordingStatus.STARTING,
            MeetingRecordingStatus.RECORDING,
            MeetingRecordingStatus.PROCESSING
    );

    private final ProjectMeetingRepository meetingRepository;
    private final LiveKitEgressClientAdapter egressClientAdapter;
    private final MeetingRecordingService meetingRecordingService;

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

        if (stuckMeetings.isEmpty()) {
            return 0;
        }

        int reconciled = 0;
        for (ProjectMeetingEntity meeting : stuckMeetings) {
            String egressId = meeting.getEgressId();
            if (egressId == null || egressId.isBlank()) {
                continue;
            }
            try {
                List<EgressSnapshot> snapshots = egressClientAdapter.listEgress(null, egressId);
                if (snapshots.isEmpty()) {
                    log.warn("Reconciliation found no LiveKit Egress record for meetingId={}, egressId={}",
                            meeting.getId(), egressId);
                    continue;
                }
                meetingRecordingService.applyEgressSnapshot(snapshots.get(0));
                reconciled++;
            } catch (Exception e) {
                log.warn("Failed to reconcile Egress {} for meeting {}: {}",
                        egressId, meeting.getId(), e.getMessage());
            }
        }
        return reconciled;
    }
}
