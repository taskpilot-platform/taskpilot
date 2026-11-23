package com.taskpilot.projects.meetings.service;

import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.config.security.FileStreamTokenService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.FileArchiveStatus;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.meetings.dto.MeetingRecordingPlaybackResponse;
import com.taskpilot.projects.meetings.dto.ProjectMeetingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
public class MeetingRecordingService {

    private static final List<MeetingRecordingStatus> ACTIVE_EGRESS_STATUSES = List.of(
            MeetingRecordingStatus.STARTING,
            MeetingRecordingStatus.RECORDING
    );

    private static final List<MeetingRecordingStatus> IN_FLIGHT_STATUSES = List.of(
            MeetingRecordingStatus.IDLE,
            MeetingRecordingStatus.STARTING,
            MeetingRecordingStatus.RECORDING,
            MeetingRecordingStatus.PROCESSING
    );

    private final ProjectMeetingRepository meetingRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMeetingService projectMeetingService;
    private final LiveKitEgressClientAdapter egressClientAdapter;
    private final EgressS3StorageService egressS3StorageService;
    private final FileStreamTokenService fileStreamTokenService;
    private final UserIdentityPort userIdentityPort;
    private final TransactionTemplate requiresNewTx;
    private final boolean gdriveArchiveEnabled;

    public MeetingRecordingService(
            ProjectMeetingRepository meetingRepository,
            ProjectFileRepository projectFileRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            ProjectMeetingService projectMeetingService,
            LiveKitEgressClientAdapter egressClientAdapter,
            EgressS3StorageService egressS3StorageService,
            FileStreamTokenService fileStreamTokenService,
            UserIdentityPort userIdentityPort,
            PlatformTransactionManager transactionManager,
            @Value("${egress.archive.gdrive.enabled:true}") boolean gdriveArchiveEnabled) {
        this.meetingRepository = meetingRepository;
        this.projectFileRepository = projectFileRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.projectMeetingService = projectMeetingService;
        this.egressClientAdapter = egressClientAdapter;
        this.egressS3StorageService = egressS3StorageService;
        this.fileStreamTokenService = fileStreamTokenService;
        this.userIdentityPort = userIdentityPort;
        this.gdriveArchiveEnabled = gdriveArchiveEnabled;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public ProjectMeetingResponse startRecording(Long projectId, Long meetingId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));

        requireHostOrManager(meeting, projectId, userId);

        if (meeting.getStatus() == ProjectMeetingStatus.ENDED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Cannot start recording on an ended meeting");
        }

        MeetingRecordingStatus current = effectiveStatus(meeting);
        if (current == MeetingRecordingStatus.STARTING
                || current == MeetingRecordingStatus.RECORDING
                || current == MeetingRecordingStatus.PROCESSING) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Recording is already in progress (status=" + current + ")");
        }

        if (!egressClientAdapter.isConfigured() || !egressS3StorageService.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Cloud Egress recording is not configured on the server");
        }

        String filepath = buildEgressFilepath(projectId, meetingId);
        EgressSnapshot snapshot;
        try {
            snapshot = egressClientAdapter.startRoomCompositeEgress(
                    meeting.getRoomName(),
                    filepath,
                    egressS3StorageService
            );
        } catch (Exception e) {
            log.error("Failed to start LiveKit Egress for meeting {}: {}", meetingId, e.getMessage(), e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY.value(),
                    "Failed to start cloud recording: " + e.getMessage());
        }

        meeting.setEgressId(snapshot.egressId());
        meeting.setRecordingEnabled(true);
        meeting.setRecordingStatus("EGRESS_ACTIVE".equals(snapshot.status())
                ? MeetingRecordingStatus.RECORDING
                : MeetingRecordingStatus.STARTING);
        meeting.setRecordingError(null);
        meeting.setBackupStorageUsed(false);
        meetingRepository.save(meeting);

        log.info("Started LiveKit RoomComposite Egress: meetingId={}, room={}, egressId={}, targetKey={}",
                meetingId, meeting.getRoomName(), snapshot.egressId(), filepath);

        return projectMeetingService.getMeetingById(projectId, meetingId, userEmail);
    }

    @Transactional
    public ProjectMeetingResponse stopRecording(Long projectId, Long meetingId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));

        requireHostOrManager(meeting, projectId, userId);

        String egressId = meeting.getEgressId();
        if (egressId == null || egressId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "No active cloud recording for this meeting");
        }

        MeetingRecordingStatus current = effectiveStatus(meeting);
        if (!ACTIVE_EGRESS_STATUSES.contains(current)) {
            return projectMeetingService.getMeetingById(projectId, meetingId, userEmail);
        }

        EgressSnapshot snapshot;
        try {
            snapshot = egressClientAdapter.stopEgress(egressId);
        } catch (Exception e) {
            log.error("Failed to stop LiveKit Egress {} for meeting {}: {}", egressId, meetingId, e.getMessage(), e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY.value(),
                    "Failed to stop cloud recording: " + e.getMessage());
        }

        if (snapshot.isTerminal()) {
            applyEgressSnapshot(snapshot);
        } else {
            meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    egressId,
                    MeetingRecordingStatus.PROCESSING,
                    null,
                    snapshot.backupStorageUsed(),
                    Instant.now(),
                    ACTIVE_EGRESS_STATUSES
            );
        }

        return projectMeetingService.getMeetingById(projectId, meetingId, userEmail);
    }

    /**
     * Best-effort stop invoked when the host ends the meeting so LiveKit Egress quota is not leaked.
     */
    @Transactional
    public void stopRecordingIfActive(ProjectMeetingEntity meeting) {
        if (meeting == null || meeting.getEgressId() == null || meeting.getEgressId().isBlank()) {
            return;
        }
        MeetingRecordingStatus current = effectiveStatus(meeting);
        if (!ACTIVE_EGRESS_STATUSES.contains(current)) {
            return;
        }
        if (!egressClientAdapter.isConfigured()) {
            return;
        }
        try {
            EgressSnapshot snapshot = egressClientAdapter.stopEgress(meeting.getEgressId());
            if (snapshot.isTerminal()) {
                applyEgressSnapshot(snapshot);
            } else {
                meetingRepository.transitionRecordingStatus(
                        meeting.getId(),
                        meeting.getEgressId(),
                        MeetingRecordingStatus.PROCESSING,
                        null,
                        snapshot.backupStorageUsed(),
                        Instant.now(),
                        ACTIVE_EGRESS_STATUSES
                );
            }
            log.info("Auto-stopped LiveKit Egress {} on endMeeting for meeting {}", meeting.getEgressId(), meeting.getId());
        } catch (Exception e) {
            log.warn("Best-effort auto-stop Egress {} on endMeeting failed (reconciliation job will sync): {}",
                    meeting.getEgressId(), e.getMessage());
        }
    }

    @Transactional
    public ProjectMeetingResponse syncRecordingStatus(Long projectId, Long meetingId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));

        if (meeting.getEgressId() != null && !meeting.getEgressId().isBlank() && egressClientAdapter.isConfigured()) {
            try {
                List<EgressSnapshot> snapshots = egressClientAdapter.listEgress(null, meeting.getEgressId());
                if (!snapshots.isEmpty()) {
                    applyEgressSnapshot(snapshots.get(0));
                }
            } catch (Exception e) {
                log.warn("Manual sync ListEgress failed for meeting {} egress {}: {}",
                        meetingId, meeting.getEgressId(), e.getMessage());
                throw new BusinessException(HttpStatus.BAD_GATEWAY.value(),
                        "Could not synchronize recording status with LiveKit: " + e.getMessage());
            }
        }

        return projectMeetingService.getMeetingById(projectId, meetingId, userEmail);
    }

    @Transactional
    public void handleWebhook(String rawBody, String authorizationHeader) {
        LiveKitEgressClientAdapter.ParsedWebhookEvent parsed =
                egressClientAdapter.receiveWebhook(rawBody, authorizationHeader);

        log.info("Received verified LiveKit webhook event='{}', egressId='{}'",
                parsed.eventName(),
                parsed.egressSnapshot() != null ? parsed.egressSnapshot().egressId() : "none");

        if (parsed.egressSnapshot() != null) {
            applyEgressSnapshot(parsed.egressSnapshot());
        }
    }

    /**
     * Race-safe, idempotent state transition shared by webhooks, startup/periodic reconciliation, and manual sync.
     */
    @Transactional
    public void applyEgressSnapshot(EgressSnapshot snapshot) {
        if (snapshot == null || snapshot.egressId() == null || snapshot.egressId().isBlank()) {
            return;
        }

        Optional<ProjectMeetingEntity> optMeeting = meetingRepository.findByEgressId(snapshot.egressId());
        if (optMeeting.isEmpty()) {
            log.debug("Ignoring Egress snapshot for unknown egressId={}", snapshot.egressId());
            return;
        }

        ProjectMeetingEntity meeting = optMeeting.get();
        Instant now = Instant.now();

        switch (snapshot.status()) {
            case "EGRESS_STARTING" -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.STARTING,
                    null,
                    snapshot.backupStorageUsed(),
                    now,
                    List.of(MeetingRecordingStatus.IDLE)
            );
            case "EGRESS_ACTIVE" -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.RECORDING,
                    null,
                    snapshot.backupStorageUsed(),
                    now,
                    List.of(MeetingRecordingStatus.IDLE, MeetingRecordingStatus.STARTING)
            );
            case "EGRESS_ENDING" -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.PROCESSING,
                    null,
                    snapshot.backupStorageUsed(),
                    now,
                    List.of(MeetingRecordingStatus.STARTING, MeetingRecordingStatus.RECORDING)
            );
            case "EGRESS_COMPLETE" -> handleCompletedSnapshot(meeting, snapshot, now);
            case "EGRESS_FAILED", "EGRESS_ABORTED", "EGRESS_LIMIT_REACHED" -> handleFailedSnapshot(meeting, snapshot, now);
            default -> log.warn("Unhandled LiveKit Egress status '{}' for egressId={}", snapshot.status(), snapshot.egressId());
        }
    }

    @Transactional(readOnly = true)
    public MeetingRecordingPlaybackResponse getPlaybackUrl(Long projectId, Long meetingId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));

        MeetingRecordingStatus status = effectiveStatus(meeting);
        boolean backupUsed = Boolean.TRUE.equals(meeting.getBackupStorageUsed());

        if (meeting.getRecordingFileId() == null) {
            return MeetingRecordingPlaybackResponse.builder()
                    .url(null)
                    .provider(null)
                    .requiresAuthHeader(false)
                    .recordingFileId(null)
                    .egressId(meeting.getEgressId())
                    .recordingStatus(status)
                    .backupStorageUsed(backupUsed)
                    .recordingError(meeting.getRecordingError())
                    .build();
        }

        ProjectFileEntity file = projectFileRepository.findByIdAndProjectId(meeting.getRecordingFileId(), projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Recording file not found"));

        String provider = resolveProvider(file);
        String playbackUrl;
        Instant expiresAt;

        if ("b2".equals(provider)) {
            if (!egressS3StorageService.isConfigured()) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "Backblaze B2 storage is not configured to generate playback URL");
            }
            EgressS3StorageService.PresignedUrlResult presigned =
                    egressS3StorageService.generatePresignedGetUrl(file.getStorageKey());
            playbackUrl = presigned.url();
            expiresAt = presigned.expiresAt();
        } else {
            // gdrive or s3: return backend Range-capable proxy URL with short-lived project-scoped stream token
            FileStreamTokenService.IssuedStreamToken issued =
                    fileStreamTokenService.issueToken(userEmail, projectId, file.getId());
            playbackUrl = "/api/v1/projects/" + projectId + "/files/" + file.getId()
                    + "/stream?token=" + issued.token();
            expiresAt = issued.expiresAt();
        }

        return MeetingRecordingPlaybackResponse.builder()
                .url(playbackUrl)
                .provider(provider)
                .requiresAuthHeader(false)
                .contentType(file.getContentType() != null ? file.getContentType() : "video/mp4")
                .fileName(file.getOriginalName())
                .fileSize(file.getFileSize())
                .expiresAt(expiresAt)
                .recordingFileId(file.getId())
                .egressId(meeting.getEgressId())
                .recordingStatus(status)
                .backupStorageUsed(backupUsed)
                .recordingError(meeting.getRecordingError())
                .build();
    }

    private void handleCompletedSnapshot(ProjectMeetingEntity meeting, EgressSnapshot snapshot, Instant now) {
        String storageKey = cleanKey(snapshot.filepath());

        if (snapshot.backupStorageUsed()) {
            // Verify whether the primary B2 bucket actually received the object; if not, surface backup recovery status.
            boolean presentOnB2 = false;
            if (!storageKey.isBlank() && egressS3StorageService.isConfigured()) {
                try {
                    EgressS3StorageService.ObjectMetadata meta = egressS3StorageService.headObject(storageKey);
                    presentOnB2 = meta.contentLength() > 0;
                } catch (IOException ignored) {
                    presentOnB2 = false;
                }
            }
            if (!presentOnB2) {
                String msg = buildBackupStorageErrorMessage(snapshot.egressId(), snapshot.error());
                log.warn("Egress {} completed with backup_storage_used=true and object missing from B2: {}",
                        snapshot.egressId(), msg);
                meetingRepository.transitionRecordingStatus(
                        meeting.getId(),
                        snapshot.egressId(),
                        MeetingRecordingStatus.FAILED,
                        msg,
                        true,
                        now,
                        IN_FLIGHT_STATUSES
                );
                return;
            }
        }

        if (storageKey.isBlank()) {
            meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.FAILED,
                    "LiveKit completed egress without an output file path",
                    snapshot.backupStorageUsed(),
                    now,
                    IN_FLIGHT_STATUSES
            );
            return;
        }

        long fileSize = snapshot.fileSize();
        if (fileSize <= 0 && egressS3StorageService.isConfigured()) {
            try {
                fileSize = egressS3StorageService.headObject(storageKey).contentLength();
            } catch (IOException e) {
                log.warn("Could not stat B2 object {} for size fallback: {}", storageKey, e.getMessage());
            }
        }

        ProjectFileEntity recordingFile = findOrCreateRecordingFile(meeting, snapshot.egressId(), storageKey, Math.max(0L, fileSize), now);

        int updated = meetingRepository.transitionRecordingCompleted(
                meeting.getId(),
                snapshot.egressId(),
                MeetingRecordingStatus.COMPLETED,
                recordingFile.getId(),
                snapshot.backupStorageUsed(),
                now,
                IN_FLIGHT_STATUSES
        );

        log.info("Applied EGRESS_COMPLETE: meetingId={}, egressId={}, fileId={}, rowsUpdated={}",
                meeting.getId(), snapshot.egressId(), recordingFile.getId(), updated);
    }

    private void handleFailedSnapshot(ProjectMeetingEntity meeting, EgressSnapshot snapshot, Instant now) {
        String errorMessage = snapshot.backupStorageUsed()
                ? buildBackupStorageErrorMessage(snapshot.egressId(), snapshot.error())
                : (snapshot.error() != null && !snapshot.error().isBlank()
                    ? snapshot.error()
                    : "Cloud recording ended with status " + snapshot.status());

        if (snapshot.backupStorageUsed()) {
            log.warn("Egress {} failed primary upload but backup_storage_used=true (LiveKit Cloud 7-day retention): {}",
                    snapshot.egressId(), errorMessage);
        } else {
            log.error("Egress {} failed for meeting {}: {}", snapshot.egressId(), meeting.getId(), errorMessage);
        }

        meetingRepository.transitionRecordingStatus(
                meeting.getId(),
                snapshot.egressId(),
                MeetingRecordingStatus.FAILED,
                errorMessage,
                snapshot.backupStorageUsed(),
                now,
                IN_FLIGHT_STATUSES
        );
    }

    /**
     * Enforces idempotent file creation backed by DB UNIQUE index on project_files(source_egress_id).
     * Uses REQUIRES_NEW so a concurrent unique-violation never poisons the outer transaction.
     */
    private ProjectFileEntity findOrCreateRecordingFile(
            ProjectMeetingEntity meeting,
            String egressId,
            String storageKey,
            long fileSize,
            Instant now) {
        Optional<ProjectFileEntity> existing = projectFileRepository.findBySourceEgressId(egressId);
        if (existing.isPresent()) {
            return existing.get();
        }

        String fileName = extractFileName(storageKey, meeting.getId());
        String bucket = egressS3StorageService.getBucket().isBlank() ? "b2-recordings" : egressS3StorageService.getBucket();
        FileArchiveStatus initialArchiveStatus = gdriveArchiveEnabled
                ? FileArchiveStatus.ARCHIVE_PENDING
                : FileArchiveStatus.NONE;

        try {
            return Objects.requireNonNull(requiresNewTx.execute(status -> {
                ProjectFileEntity entity = ProjectFileEntity.builder()
                        .projectId(meeting.getProjectId())
                        .uploaderId(meeting.getHostId())
                        .fileName(fileName)
                        .originalName(fileName)
                        .fileSize(fileSize)
                        .contentType("video/mp4")
                        .storageKey(storageKey)
                        .storageBucket(bucket)
                        .storageProvider("b2")
                        .sourceEgressId(egressId)
                        .archiveStatus(initialArchiveStatus)
                        .archiveRetryCount(0)
                        .description("Cloud recording (LiveKit Egress): " + meeting.getTitle())
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                return projectFileRepository.saveAndFlush(entity);
            }));
        } catch (DataIntegrityViolationException raceLost) {
            log.info("Concurrent Egress file insert detected for egressId={}, reusing existing ProjectFileEntity", egressId);
            return projectFileRepository.findBySourceEgressId(egressId)
                    .orElseThrow(() -> raceLost);
        }
    }

    private void requireHostOrManager(ProjectMeetingEntity meeting, Long projectId, Long userId) {
        boolean isHost = Objects.equals(meeting.getHostId(), userId)
                || projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                    .map(m -> m.getRole() == MemberRole.MANAGER)
                    .orElse(false);
        if (!isHost) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only the meeting host or project manager can control cloud recording");
        }
    }

    private Long getUserId(String email) {
        return userIdentityPort.findByEmail(email)
                .map(u -> u.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }

    private static MeetingRecordingStatus effectiveStatus(ProjectMeetingEntity meeting) {
        return meeting.getRecordingStatus() != null ? meeting.getRecordingStatus() : MeetingRecordingStatus.IDLE;
    }

    private static String resolveProvider(ProjectFileEntity file) {
        if (file.getStorageProvider() != null && !file.getStorageProvider().isBlank()) {
            return file.getStorageProvider().toLowerCase();
        }
        return "googledrive".equalsIgnoreCase(file.getStorageBucket()) ? "gdrive" : "s3";
    }

    private static String buildEgressFilepath(Long projectId, Long meetingId) {
        return "recordings/project-" + projectId + "/meeting-" + meetingId + "-" + Instant.now().getEpochSecond() + ".mp4";
    }

    private static String extractFileName(String storageKey, Long meetingId) {
        int lastSlash = storageKey.lastIndexOf('/');
        String candidate = lastSlash >= 0 ? storageKey.substring(lastSlash + 1) : storageKey;
        return candidate.isBlank() ? ("meeting_" + meetingId + "_recording.mp4") : candidate;
    }

    private static String cleanKey(String key) {
        if (key == null) {
            return "";
        }
        String trimmed = key.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static String buildBackupStorageErrorMessage(String egressId, String rawError) {
        String detail = (rawError != null && !rawError.isBlank()) ? (" (" + rawError + ")") : "";
        return "Upload to primary storage failed; recording is stored in LiveKit Cloud backup storage (~7-day retention). "
                + "Recover via LiveKit Cloud Dashboard / Support using Egress ID: " + egressId + detail;
    }
}
