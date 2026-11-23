package com.taskpilot.projects.meetings.service;

import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.contracts.storage.StorageTargetDomain;
import com.taskpilot.contracts.storage.StorageUploadOperationDto;
import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.contracts.storage.StorageUploadReservationCommand;
import com.taskpilot.infrastructure.config.security.FileStreamTokenService;
import com.taskpilot.infrastructure.config.security.PresignedUploadTokenService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.observability.TaskPilotMetrics;
import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.FileArchiveStatus;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.MeetingRecordingRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.meetings.dto.CompleteRecordingUploadRequest;
import com.taskpilot.projects.meetings.dto.MeetingRecordingPlaybackResponse;
import com.taskpilot.projects.meetings.dto.PresignRecordingUploadRequest;
import com.taskpilot.projects.meetings.dto.PresignRecordingUploadResponse;
import com.taskpilot.projects.meetings.dto.ProjectMeetingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class MeetingRecordingService {

    private static final List<MeetingRecordingStatus> ACTIVE_EGRESS_STATUSES = List.of(
            MeetingRecordingStatus.STARTING,
            MeetingRecordingStatus.RECORDING);

    private static final List<MeetingRecordingStatus> IN_FLIGHT_STATUSES = List.of(
            MeetingRecordingStatus.IDLE,
            MeetingRecordingStatus.STARTING,
            MeetingRecordingStatus.RECORDING,
            MeetingRecordingStatus.PROCESSING);

    private static final List<MeetingRecordingStatus> RECOVERABLE_COMPLETION_STATUSES = List.of(
            MeetingRecordingStatus.IDLE,
            MeetingRecordingStatus.STARTING,
            MeetingRecordingStatus.RECORDING,
            MeetingRecordingStatus.PROCESSING,
            MeetingRecordingStatus.FAILED);

    private static final Map<String, String> ALLOWED_FALLBACK_CONTENT_TYPES = Map.of(
            "video/webm", ".webm",
            "video/mp4", ".mp4",
            "video/x-matroska", ".mkv");

    private final ProjectMeetingRepository meetingRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMeetingService projectMeetingService;
    private final LiveKitEgressClientAdapter egressClientAdapter;
    private final EgressS3StorageService egressS3StorageService;
    private final FileStreamTokenService fileStreamTokenService;
    private final PresignedUploadTokenService presignedUploadTokenService;
    private final TransactionTemplate requiresNewTx;
    private final boolean gdriveArchiveEnabled;
    private final long maxFallbackFileSizeBytes;
    private final long uploadTokenGraceMinutes;
    private TaskPilotMetrics metrics = TaskPilotMetrics.noop();
    private MeetingRecordingRepository meetingRecordingRepository;
    private StorageUploadOperationPort storageUploadOperationPort;

    @Autowired(required = false)
    public void setMetrics(TaskPilotMetrics metrics) {
        this.metrics = metrics != null ? metrics : TaskPilotMetrics.noop();
    }

    @Autowired(required = false)
    public void setMeetingRecordingRepository(MeetingRecordingRepository meetingRecordingRepository) {
        this.meetingRecordingRepository = meetingRecordingRepository;
    }

    @Autowired(required = false)
    public void setStorageUploadOperationPort(StorageUploadOperationPort storageUploadOperationPort) {
        this.storageUploadOperationPort = storageUploadOperationPort;
    }

    public MeetingRecordingService(
            ProjectMeetingRepository meetingRepository,
            ProjectFileRepository projectFileRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            ProjectMeetingService projectMeetingService,
            LiveKitEgressClientAdapter egressClientAdapter,
            EgressS3StorageService egressS3StorageService,
            FileStreamTokenService fileStreamTokenService,
            PresignedUploadTokenService presignedUploadTokenService,
            PlatformTransactionManager transactionManager,
            boolean gdriveArchiveEnabled,
            long maxFallbackFileSizeBytes) {
        this(meetingRepository, projectFileRepository, projectMemberRepository, projectSecurityService,
                projectMeetingService, egressClientAdapter, egressS3StorageService, fileStreamTokenService,
                presignedUploadTokenService, transactionManager,
                gdriveArchiveEnabled, maxFallbackFileSizeBytes, 60L);
    }

    @Autowired
    public MeetingRecordingService(
            ProjectMeetingRepository meetingRepository,
            ProjectFileRepository projectFileRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            ProjectMeetingService projectMeetingService,
            LiveKitEgressClientAdapter egressClientAdapter,
            EgressS3StorageService egressS3StorageService,
            FileStreamTokenService fileStreamTokenService,
            PresignedUploadTokenService presignedUploadTokenService,
            PlatformTransactionManager transactionManager,
            @Value("${egress.archive.gdrive.enabled:true}") boolean gdriveArchiveEnabled,
            @Value("${egress.fallback.max-file-size-bytes:1073741824}") long maxFallbackFileSizeBytes,
            @Value("${egress.fallback.upload-token-grace-minutes:60}") long uploadTokenGraceMinutes) {
        this.meetingRepository = meetingRepository;
        this.projectFileRepository = projectFileRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.projectMeetingService = projectMeetingService;
        this.egressClientAdapter = egressClientAdapter;
        this.egressS3StorageService = egressS3StorageService;
        this.fileStreamTokenService = fileStreamTokenService;
        this.presignedUploadTokenService = presignedUploadTokenService;
        this.gdriveArchiveEnabled = gdriveArchiveEnabled;
        this.maxFallbackFileSizeBytes = Math.max(1024L * 1024L, maxFallbackFileSizeBytes);
        this.uploadTokenGraceMinutes = Math.max(0L, uploadTokenGraceMinutes);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ProjectMeetingResponse startRecording(Long projectId, Long meetingId, Long userId) {
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));

        requireHostOrManager(meeting, projectId, userId);

        if (meeting.getStatus() == ProjectMeetingStatus.ENDED || meeting.getStatus() == ProjectMeetingStatus.ENDING) {
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

        // Atomic claim in a short transaction BEFORE remote LiveKit RPC so concurrent
        // requests cannot spawn duplicate Egress jobs.
        Integer claimed = requiresNewTx.execute(tx -> meetingRepository.claimRecordingStart(meetingId, Instant.now()));
        if (claimed == null || claimed == 0) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Recording is already starting or in progress");
        }

        String filepath = buildEgressFilepath(projectId, meetingId);
        EgressSnapshot snapshot;
        io.micrometer.observation.Observation startObs = io.micrometer.observation.Observation
                .createNotStarted("taskpilot.egress.start", metrics.getObservationRegistry())
                .lowCardinalityKeyValue("mode", "cloud")
                .start();
        try (io.micrometer.observation.Observation.Scope ignored = startObs.openScope()) {
            snapshot = egressClientAdapter.startRoomCompositeEgress(
                    meeting.getRoomName(),
                    filepath,
                    egressS3StorageService);
        } catch (Exception e) {
            startObs.error(e);
            metrics.recordEgressRecording("cloud", "start_failed");
            requiresNewTx.execute(tx -> meetingRepository.releaseFailedStartClaim(
                    meetingId,
                    truncateError("Failed to start cloud recording: " + e.getMessage()),
                    Instant.now()));
            log.error("Failed to start LiveKit Egress for meeting {}: {}", meetingId, e.getMessage(), e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY.value(),
                    "Failed to start cloud recording: " + e.getMessage());
        } finally {
            startObs.stop();
        }

        MeetingRecordingStatus nextStatus = "EGRESS_ACTIVE".equals(snapshot.status())
                ? MeetingRecordingStatus.RECORDING
                : MeetingRecordingStatus.STARTING;
        ProjectMeetingEntity attachedMeeting = requiresNewTx.execute(tx -> {
            meetingRepository.attachStartedEgress(
                    meetingId,
                    snapshot.egressId(),
                    nextStatus,
                    Instant.now());
            return meetingRepository.findByIdAndProjectId(meetingId, projectId).orElse(null);
        });

        if (attachedMeeting != null
                && (attachedMeeting.getStatus() == ProjectMeetingStatus.ENDING
                        || attachedMeeting.getStatus() == ProjectMeetingStatus.ENDED)) {
            log.warn("Meeting {} transitioned to {} while LiveKit Egress {} was starting; requesting stop immediately",
                    meetingId, attachedMeeting.getStatus(), snapshot.egressId());
            stopRecordingIfActive(attachedMeeting);
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Meeting ended while cloud recording was starting; recording stop has been requested");
        }
        metrics.recordEgressRecording("cloud", "started");

        log.info("Started LiveKit RoomComposite Egress: meetingId={}, room={}, egressId={}, targetKey={}",
                meetingId, meeting.getRoomName(), snapshot.egressId(), filepath);

        return projectMeetingService.getMeetingById(projectId, meetingId, userId);
    }

    public ProjectMeetingResponse stopRecording(Long projectId, Long meetingId, Long userId) {
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
            return projectMeetingService.getMeetingById(projectId, meetingId, userId);
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
            requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    egressId,
                    MeetingRecordingStatus.PROCESSING,
                    null,
                    snapshot.backupStorageUsed(),
                    Instant.now(),
                    ACTIVE_EGRESS_STATUSES));
        }

        return projectMeetingService.getMeetingById(projectId, meetingId, userId);
    }

    /**
     * Best-effort stop invoked when the host ends the meeting so LiveKit Egress
     * quota is not leaked.
     */
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
                requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                        meeting.getId(),
                        meeting.getEgressId(),
                        MeetingRecordingStatus.PROCESSING,
                        null,
                        snapshot.backupStorageUsed(),
                        Instant.now(),
                        ACTIVE_EGRESS_STATUSES));
            }
            log.info("Auto-stopped LiveKit Egress {} on endMeeting for meeting {}", meeting.getEgressId(),
                    meeting.getId());
        } catch (Exception e) {
            log.warn("Best-effort auto-stop Egress {} on endMeeting failed (reconciliation job will sync): {}",
                    meeting.getEgressId(), e.getMessage());
        }
    }

    public ProjectMeetingResponse syncRecordingStatus(Long projectId, Long meetingId, Long userId) {
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

        return projectMeetingService.getMeetingById(projectId, meetingId, userId);
    }

    public void markMissingEgressFailed(Long meetingId, String egressId, String reason) {
        requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                meetingId,
                egressId,
                MeetingRecordingStatus.FAILED,
                truncateError(reason),
                false,
                Instant.now(),
                IN_FLIGHT_STATUSES));
    }

    public void recoverStartingClaim(Long meetingId, EgressSnapshot snapshot) {
        if (snapshot == null || snapshot.egressId() == null || snapshot.egressId().isBlank()) {
            return;
        }
        MeetingRecordingStatus initialStatus = "EGRESS_ACTIVE".equals(snapshot.status())
                ? MeetingRecordingStatus.RECORDING
                : MeetingRecordingStatus.STARTING;
        Integer attached = requiresNewTx.execute(tx -> meetingRepository.attachStartedEgress(
                meetingId,
                snapshot.egressId(),
                initialStatus,
                Instant.now()));
        if (attached != null && attached > 0) {
            applyEgressSnapshot(snapshot);
            if (!snapshot.isTerminal()) {
                meetingRepository.findById(meetingId)
                        .filter(m -> m.getStatus() == ProjectMeetingStatus.ENDING
                                || m.getStatus() == ProjectMeetingStatus.ENDED)
                        .ifPresent(this::stopRecordingIfActive);
            }
        }
    }

    public void releaseStaleStartingClaim(Long meetingId, String reason) {
        requiresNewTx.execute(tx -> meetingRepository.releaseFailedStartClaim(
                meetingId,
                truncateError(reason),
                Instant.now()));
    }

    public void handleWebhook(String rawBody, String authorizationHeader) {
        metrics.observeRunnable("taskpilot.egress.webhook", "mode", "cloud", () -> {
            LiveKitEgressClientAdapter.ParsedWebhookEvent parsed =
                    egressClientAdapter.receiveWebhook(rawBody, authorizationHeader);

            metrics.recordLiveKitWebhook(parsed.eventName(), "accepted");
            log.info("Received verified LiveKit webhook event='{}', egressId='{}'",
                    parsed.eventName(),
                    parsed.egressSnapshot() != null ? parsed.egressSnapshot().egressId() : "none");

            if (parsed.egressSnapshot() != null) {
                applyEgressSnapshot(parsed.egressSnapshot());
            }
            if (java.util.Set.of("room_started", "room_finished", "participant_joined", "participant_left")
                    .contains(parsed.eventName())
                    && parsed.roomName() != null && parsed.roomName().startsWith("tp-proj-")
                    && parsed.roomName().length() <= 128) {
                projectMeetingService.reconcileVerifiedRoomEvent(parsed.roomName());
            }
        });
    }

    /**
     * Race-safe, idempotent state transition shared by webhooks, startup/periodic
     * reconciliation,
     * stopRecording, and manual sync. Uses explicit {@code requiresNewTx} for all
     * modifying JPQL
     * queries so internal callers never bypass Spring's transactional proxy and
     * remote B2 I/O never
     * holds a DB transaction open.
     */
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
            case "EGRESS_STARTING" -> requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.STARTING,
                    null,
                    snapshot.backupStorageUsed(),
                    now,
                    List.of(MeetingRecordingStatus.IDLE)));
            case "EGRESS_ACTIVE" -> requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.RECORDING,
                    null,
                    snapshot.backupStorageUsed(),
                    now,
                    List.of(MeetingRecordingStatus.IDLE, MeetingRecordingStatus.STARTING)));
            case "EGRESS_ENDING" -> requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.PROCESSING,
                    null,
                    snapshot.backupStorageUsed(),
                    now,
                    List.of(MeetingRecordingStatus.STARTING, MeetingRecordingStatus.RECORDING)));
            case "EGRESS_COMPLETE" -> handleCompletedSnapshot(meeting, snapshot, now);
            case "EGRESS_FAILED", "EGRESS_ABORTED", "EGRESS_LIMIT_REACHED" ->
                handleFailedSnapshot(meeting, snapshot, now);
            default -> log.warn("Unhandled LiveKit Egress status '{}' for egressId={}", snapshot.status(),
                    snapshot.egressId());
        }
    }

    @Transactional(readOnly = true)
    public MeetingRecordingPlaybackResponse getPlaybackUrl(Long projectId, Long meetingId, Long userId) {
        return getPlaybackUrl(projectId, meetingId, userId, null);
    }

    @Transactional(readOnly = true)
    public MeetingRecordingPlaybackResponse getPlaybackUrl(Long projectId, Long meetingId, Long userId,
            Long requestedFileId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));

        MeetingRecordingStatus status = effectiveStatus(meeting);
        boolean backupUsed = Boolean.TRUE.equals(meeting.getBackupStorageUsed());

        Long targetFileId;
        if (requestedFileId != null) {
            boolean matchesLatest = Objects.equals(requestedFileId, meeting.getRecordingFileId());
            boolean matchesHistory = !matchesLatest && meetingRecordingRepository != null
                    && meetingRecordingRepository.findByProjectFileId(requestedFileId)
                            .filter(rec -> Objects.equals(rec.getProjectId(), projectId)
                                    && Objects.equals(rec.getMeetingId(), meetingId)
                                    && rec.getState() == MeetingRecordingEntity.State.READY)
                            .isPresent();
            if (!matchesLatest && !matchesHistory) {
                throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Recording file not found for this meeting");
            }
            targetFileId = requestedFileId;
        } else {
            targetFileId = meeting.getRecordingFileId();
        }

        if (targetFileId == null) {
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

        ProjectFileEntity file = projectFileRepository.findByIdAndProjectId(targetFileId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Recording file not found"));

        String provider = resolveProvider(file);
        if ("b2".equals(provider) && !egressS3StorageService.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Backblaze B2 storage is not configured to serve recording playback");
        }
        // Always serve playback through the authenticated backend Range-streaming proxy
        // (b2, gdrive, s3)
        // so project membership, account lifecycle, and token_version revocations take
        // effect on subsequent requests.
        FileStreamTokenService.IssuedStreamToken issued = fileStreamTokenService.issueToken(userId, projectId,
                file.getId());
        String playbackUrl = "/api/v1/projects/" + projectId + "/files/" + file.getId()
                + "/stream?token=" + issued.token();
        Instant expiresAt = issued.expiresAt();

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

    /**
     * Tier 3 (client fallback): issues a short-lived Backblaze B2 presigned PUT URL
     * locked to
     * a server-generated object key under
     * {@code recordings/{projectId}/{meetingId}/} and exact Content-Type.
     */
    @Transactional(readOnly = true)
    public PresignRecordingUploadResponse presignFallbackUpload(
            Long projectId,
            Long meetingId,
            Long userId,
            PresignRecordingUploadRequest request) {
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));
        requireHostOrManager(meeting, projectId, userId);

        MeetingRecordingStatus currentStatus = effectiveStatus(meeting);
        if (currentStatus == MeetingRecordingStatus.STARTING
                || currentStatus == MeetingRecordingStatus.RECORDING
                || currentStatus == MeetingRecordingStatus.PROCESSING) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Cannot start fallback upload while a cloud recording is in progress (status=" + currentStatus
                            + ")");
        }

        if (!egressS3StorageService.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Backblaze B2 recording storage is not configured");
        }

        String canonicalContentType = normalizeVideoContentType(request.contentType());
        String ext = ALLOWED_FALLBACK_CONTENT_TYPES.get(canonicalContentType);
        if (ext == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Unsupported recording Content-Type: " + request.contentType()
                            + ". Allowed: video/webm, video/mp4, video/x-matroska");
        }

        String objectKey = "recordings/" + projectId + "/" + meetingId + "/" + UUID.randomUUID() + ext;
        String safeFileName = sanitizeFallbackFileName(request.fileName(), meetingId, ext);
        String bucket = egressS3StorageService.getBucket().isBlank() ? "b2-recordings"
                : egressS3StorageService.getBucket();
        String clientRecordingId = normalizeClientRecordingId(request.clientRecordingId());

        if (clientRecordingId != null && meetingRecordingRepository != null) {
            UUID clipId = deterministicClipId(projectId, meetingId,
                    fallbackLogicalKey(userId, clientRecordingId, null));
            Optional<MeetingRecordingEntity> existingClip = meetingRecordingRepository.findById(clipId);
            if (existingClip.isPresent()
                    && existingClip.get().getState() == MeetingRecordingEntity.State.READY
                    && existingClip.get().getProjectFileId() != null) {
                log.info("Idempotent presignFallbackUpload reused already-completed logical clip={} (fileId={})",
                        clientRecordingId, existingClip.get().getProjectFileId());
                return PresignRecordingUploadResponse.builder()
                        .alreadyCompleted(true)
                        .existingFileId(existingClip.get().getProjectFileId())
                        .contentType(canonicalContentType)
                        .fileName(safeFileName)
                        .maxFileSizeBytes(maxFallbackFileSizeBytes)
                        .build();
            }
        }

        // Completion uploadToken outlives the short presigned PUT start URL by
        // uploadTokenGraceMinutes
        // so large recordings never fail at complete-upload after PUT finishes.
        // The durable upload reservation lease adds an extra 15-minute reconciliation
        // safety buffer past uploadTokenTtl
        // so late valid PUTs that started before URL expiry cannot be cleaned up before
        // completion deadline expires.
        java.time.Duration uploadTokenTtl = egressS3StorageService.getPresignPutTtl()
                .plusMinutes(uploadTokenGraceMinutes);
        java.time.Duration reservationLease = uploadTokenTtl.plusMinutes(15);

        UUID operationId = null;
        if (storageUploadOperationPort != null) {
            StorageUploadOperationDto reserved = storageUploadOperationPort.reserve(new StorageUploadReservationCommand(
                    userId,
                    projectId,
                    StorageTargetDomain.PROJECT_FILE,
                    StorageProviderKind.B2,
                    bucket,
                    objectKey,
                    safeFileName,
                    canonicalContentType,
                    0L,
                    reservationLease));
            operationId = reserved.operationId();
        }

        EgressS3StorageService.PresignedUrlResult presigned;
        try {
            presigned = egressS3StorageService.generatePresignedPutUrl(objectKey, canonicalContentType);
        } catch (RuntimeException ex) {
            if (storageUploadOperationPort != null && operationId != null) {
                storageUploadOperationPort.markAborted(operationId, "B2_PRESIGN_FAILED", ex.getMessage());
            }
            throw ex;
        }

        String uploadToken = presignedUploadTokenService.issueToken(
                projectId,
                meetingId,
                userId,
                objectKey,
                canonicalContentType,
                safeFileName,
                uploadTokenTtl,
                operationId,
                presigned.expiresAt(),
                clientRecordingId);

        return PresignRecordingUploadResponse.builder()
                .uploadUrl(presigned.url())
                .uploadToken(uploadToken)
                .contentType(canonicalContentType)
                .fileName(safeFileName)
                .maxFileSizeBytes(maxFallbackFileSizeBytes)
                .expiresAt(presigned.expiresAt())
                .build();
    }

    /**
     * Verifies the server-issued uploadToken, checks the uploaded B2 object via
     * HeadObject,
     * deletes and rejects oversized objects, and idempotently links the recording
     * to the meeting.
     * Runs remote B2 I/O outside any DB transaction and uses unique B2 key lookup +
     * REQUIRES_NEW
     * so retries/replays never create duplicate ProjectFileEntity rows sharing one
     * B2 key.
     */
    public ProjectMeetingResponse completeFallbackUpload(
            Long projectId,
            Long meetingId,
            Long userId,
            CompleteRecordingUploadRequest request) {
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));
        requireHostOrManager(meeting, projectId, userId);

        MeetingRecordingStatus currentStatus = effectiveStatus(meeting);
        if (currentStatus == MeetingRecordingStatus.STARTING
                || currentStatus == MeetingRecordingStatus.RECORDING
                || currentStatus == MeetingRecordingStatus.PROCESSING) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Cannot link fallback recording while a cloud recording is in progress (status=" + currentStatus
                            + ")");
        }

        PresignedUploadTokenService.UploadTokenClaims claims = presignedUploadTokenService
                .verifyToken(request.uploadToken(), projectId, meetingId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Invalid or expired recording upload token"));

        if (!egressS3StorageService.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Backblaze B2 recording storage is not configured");
        }

        String clientRecordingId = claims.clientRecordingId() != null
                ? claims.clientRecordingId()
                : normalizeClientRecordingId(request.clientRecordingId());
        String logicalKey = fallbackLogicalKey(userId, clientRecordingId, claims.objectKey());
        String bucket = egressS3StorageService.getBucket().isBlank() ? "b2-recordings"
                : egressS3StorageService.getBucket();

        // Idempotency guard 1: if this uploadToken/objectKey was already registered,
        // reuse existing row cleanly without rewinding meeting.recordingFileId if a
        // newer clip exists.
        Optional<ProjectFileEntity> existingFile = projectFileRepository.findByProjectIdAndStorageKey(projectId,
                claims.objectKey());
        if (existingFile.isPresent()) {
            ProjectFileEntity reused = existingFile.get();
            Instant now = Instant.now();
            boolean alreadyRecorded = meetingRecordingRepository != null
                    && meetingRecordingRepository.findByProjectFileId(reused.getId()).isPresent();
            if (!alreadyRecorded) {
                linkExistingFallbackFileAtomically(meeting, reused, userId, logicalKey, request, now);
            }
            log.info("Idempotent completeFallbackUpload reused existing fileId={} for key={} (alreadyRecorded={})",
                    reused.getId(), claims.objectKey(), alreadyRecorded);
            return withCompletedFileReceipt(
                    projectMeetingService.getMeetingById(projectId, meetingId, userId),
                    reused.getId());
        }

        // Idempotency guard 2: if another upload attempt for this same logical client
        // clip already committed,
        // durably compensate this losing duplicate B2 object and return a receipt
        // pointing to the winner file
        // without mutating meeting.recordingFileId.
        if (clientRecordingId != null && meetingRecordingRepository != null) {
            UUID clipId = deterministicClipId(projectId, meetingId, logicalKey);
            Optional<MeetingRecordingEntity> existingClip = meetingRecordingRepository.findById(clipId);
            if (existingClip.isPresent()
                    && existingClip.get().getState() == MeetingRecordingEntity.State.READY
                    && existingClip.get().getProjectFileId() != null) {
                Optional<ProjectFileEntity> winnerFile = projectFileRepository
                        .findById(existingClip.get().getProjectFileId());
                if (winnerFile.isPresent()) {
                    if (!Objects.equals(winnerFile.get().getStorageKey(), claims.objectKey())) {
                        compensateRejectedFallbackObject(
                                claims,
                                bucket,
                                "B2_FALLBACK_DUPLICATE_CLIP",
                                "Logical recording clip already committed under fileId=" + winnerFile.get().getId());
                    }
                    log.info("Idempotent completeFallbackUpload deduplicated logical clip={} -> winner fileId={}",
                            clientRecordingId, winnerFile.get().getId());
                    return withCompletedFileReceipt(
                            projectMeetingService.getMeetingById(projectId, meetingId, userId),
                            winnerFile.get().getId());
                }
            }
        }

        EgressS3StorageService.ObjectMetadata meta;
        try {
            meta = egressS3StorageService.headObject(claims.objectKey());
        } catch (EgressS3StorageService.ObjectNotFoundException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Uploaded recording object was not found in Backblaze B2");
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Could not verify uploaded recording in Backblaze B2; please retry");
        }

        if (meta.contentLength() <= 0) {
            compensateRejectedFallbackObject(claims, bucket, "B2_FALLBACK_EMPTY", "Uploaded recording file is empty");
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Uploaded recording file is empty and was removed");
        }

        if (meta.contentLength() > maxFallbackFileSizeBytes) {
            compensateRejectedFallbackObject(claims, bucket, "B2_FALLBACK_OVERSIZED",
                    "Uploaded recording exceeded size limit: " + meta.contentLength());
            log.warn("Queued cleanup for oversized client fallback recording from B2: key={}, size={}, limit={}",
                    claims.objectKey(), meta.contentLength(), maxFallbackFileSizeBytes);
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "Recording exceeds maximum allowed size of " + (maxFallbackFileSizeBytes / (1024 * 1024)) + " MB");
        }

        if (storageUploadOperationPort != null && claims.operationId() != null) {
            try {
                storageUploadOperationPort.markProviderWritten(claims.operationId(), claims.objectKey());
            } catch (IllegalStateException expiredOrCompensated) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Recording upload reservation expired before completion");
            }
        }
        metrics.recordEgressRecording("fallback", "completed");

        Instant now = Instant.now();
        ProjectFileEntity savedFile = commitFallbackUploadAtomically(
                meeting, userId, claims, meta.contentLength(), clientRecordingId, logicalKey, request, now);

        log.info("Completed fallback B2 recording upload: meetingId={}, fileId={}, key={}, size={}",
                meetingId, savedFile.getId(), claims.objectKey(), meta.contentLength());

        return withCompletedFileReceipt(
                projectMeetingService.getMeetingById(projectId, meetingId, userId),
                savedFile.getId());
    }

    private static ProjectMeetingResponse withCompletedFileReceipt(ProjectMeetingResponse response,
            Long completedFileId) {
        if (response == null || completedFileId == null) {
            return response;
        }
        return response.toBuilder().completedRecordingFileId(completedFileId).build();
    }

    private static final class DuplicateLogicalClipException extends RuntimeException {
        private final long winnerFileId;

        private DuplicateLogicalClipException(long winnerFileId) {
            super("Logical clip already committed under fileId=" + winnerFileId);
            this.winnerFileId = winnerFileId;
        }
    }

    private ProjectFileEntity commitFallbackUploadAtomically(
            ProjectMeetingEntity meeting,
            Long userId,
            PresignedUploadTokenService.UploadTokenClaims claims,
            long contentLength,
            String clientRecordingId,
            String logicalKey,
            CompleteRecordingUploadRequest request,
            Instant now) {
        String bucket = egressS3StorageService.getBucket().isBlank() ? "b2-recordings"
                : egressS3StorageService.getBucket();
        FileArchiveStatus initialArchiveStatus = gdriveArchiveEnabled
                ? FileArchiveStatus.ARCHIVE_PENDING
                : FileArchiveStatus.NONE;

        try {
            return Objects.requireNonNull(requiresNewTx.execute(status -> {
                // Serialize concurrent completions for the same meeting before mutating
                // file/operation/meeting rows.
                meetingRepository.findForUpdate(meeting.getId(), meeting.getProjectId());

                // Same-token/same-key concurrent retry: if winner already committed this exact objectKey,
                // return it directly without rolling back or triggering compensation/deletion.
                Optional<ProjectFileEntity> alreadyCommittedSameKey = projectFileRepository
                        .findByProjectIdAndStorageKey(meeting.getProjectId(), claims.objectKey());
                if (alreadyCommittedSameKey.isPresent()) {
                    ProjectFileEntity winnerSameKey = alreadyCommittedSameKey.get();
                    boolean alreadyRecorded = meetingRecordingRepository != null
                            && meetingRecordingRepository.findByProjectFileId(winnerSameKey.getId()).isPresent();
                    if (!alreadyRecorded) {
                        upsertFallbackRecordingHistoryInCurrentTx(
                                meeting, winnerSameKey, userId, logicalKey, request, now);
                    }
                    return winnerSameKey;
                }

                if (clientRecordingId != null && meetingRecordingRepository != null) {
                    UUID clipId = deterministicClipId(meeting.getProjectId(), meeting.getId(), logicalKey);
                    Optional<MeetingRecordingEntity> existingClip = meetingRecordingRepository.findById(clipId);
                    if (existingClip.isPresent()
                            && existingClip.get().getState() == MeetingRecordingEntity.State.READY
                            && existingClip.get().getProjectFileId() != null) {
                        throw new DuplicateLogicalClipException(existingClip.get().getProjectFileId());
                    }
                }

                ProjectFileEntity saved = projectFileRepository.saveAndFlush(
                        ProjectFileEntity.builder()
                                .projectId(meeting.getProjectId())
                                .uploaderId(userId)
                                .fileName(claims.fileName())
                                .originalName(claims.fileName())
                                .fileSize(contentLength)
                                .contentType(claims.contentType())
                                .storageKey(claims.objectKey())
                                .storageBucket(bucket)
                                .storageProvider("b2")
                                .archiveStatus(initialArchiveStatus)
                                .archiveRetryCount(0)
                                .description("Fallback browser recording: " + meeting.getTitle())
                                .createdAt(now)
                                .updatedAt(now)
                                .build());
                if (storageUploadOperationPort != null && claims.operationId() != null) {
                    storageUploadOperationPort.markAttachedInCurrentTransaction(claims.operationId(), saved.getId());
                }
                int linked = meetingRepository.linkFallbackRecording(meeting.getId(), saved.getId(), now);
                if (linked == 0) {
                    throw new BusinessException(HttpStatus.CONFLICT.value(),
                            "Cannot link fallback recording because a cloud recording became active");
                }
                upsertFallbackRecordingHistoryInCurrentTx(meeting, saved, userId, logicalKey, request, now);
                return saved;
            }));
        } catch (DuplicateLogicalClipException duplicateClip) {
            // Transaction rolled back cleanly; compensate ONLY if this request uploaded a DIFFERENT B2 object key
            // than the winning file (never delete/compensate the winner's own B2 object on same-token concurrency).
            ProjectFileEntity winnerFile = projectFileRepository.findById(duplicateClip.winnerFileId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT.value(),
                            "Recording clip already committed under fileId=" + duplicateClip.winnerFileId));
            if (!Objects.equals(winnerFile.getStorageKey(), claims.objectKey())) {
                compensateRejectedFallbackObject(
                        claims,
                        bucket,
                        "B2_FALLBACK_DUPLICATE_CLIP",
                        "Concurrent duplicate upload rolled back in favor of fileId=" + duplicateClip.winnerFileId
                );
            }
            return winnerFile;
        } catch (IllegalStateException expiredOrCompensated) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Recording upload reservation expired before attachment");
        } catch (DataIntegrityViolationException raceLost) {
            log.info("Concurrent fallback upload completion detected for key={}, checking existing clip/file",
                    claims.objectKey());
            Optional<ProjectFileEntity> sameKeyFile = projectFileRepository
                    .findByProjectIdAndStorageKey(meeting.getProjectId(), claims.objectKey());
            if (sameKeyFile.isPresent()) {
                boolean alreadyRecorded = meetingRecordingRepository != null
                        && meetingRecordingRepository.findByProjectFileId(sameKeyFile.get().getId()).isPresent();
                if (!alreadyRecorded) {
                    linkExistingFallbackFileAtomically(meeting, sameKeyFile.get(), userId, logicalKey, request, now);
                }
                return sameKeyFile.get();
            }
            if (clientRecordingId != null && meetingRecordingRepository != null) {
                UUID clipId = deterministicClipId(meeting.getProjectId(), meeting.getId(), logicalKey);
                Optional<MeetingRecordingEntity> winnerClip = meetingRecordingRepository.findById(clipId);
                if (winnerClip.isPresent() && winnerClip.get().getProjectFileId() != null) {
                    Optional<ProjectFileEntity> winnerFile = projectFileRepository
                            .findById(winnerClip.get().getProjectFileId());
                    if (winnerFile.isPresent()) {
                        if (!Objects.equals(winnerFile.get().getStorageKey(), claims.objectKey())) {
                            compensateRejectedFallbackObject(
                                    claims,
                                    bucket,
                                    "B2_FALLBACK_DUPLICATE_CLIP",
                                    "Concurrent duplicate upload compensated in favor of fileId="
                                            + winnerFile.get().getId());
                        }
                        return winnerFile.get();
                    }
                }
            }
            throw raceLost;
        }
    }

    private void linkExistingFallbackFileAtomically(
            ProjectMeetingEntity meeting,
            ProjectFileEntity existing,
            Long userId,
            String logicalKey,
            CompleteRecordingUploadRequest request,
            Instant now) {
        requiresNewTx.executeWithoutResult(tx -> {
            int linked = meetingRepository.linkFallbackRecording(meeting.getId(), existing.getId(), now);
            if (linked == 0) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Cannot link fallback recording while a cloud recording is in progress");
            }
            upsertFallbackRecordingHistoryInCurrentTx(meeting, existing, userId, logicalKey, request, now);
        });
    }

    private static final java.time.Duration POST_PUT_CLEANUP_GRACE = java.time.Duration.ofMinutes(5);

    private void compensateRejectedFallbackObject(
            PresignedUploadTokenService.UploadTokenClaims claims,
            String bucket,
            String errorCode,
            String errorMessage) {
        // Defense-in-depth: never compensate or delete a B2 object key already owned by a committed ProjectFileEntity.
        if (projectFileRepository.findByProjectIdAndStorageKey(claims.projectId(), claims.objectKey()).isPresent()) {
            log.warn("Skipped B2 compensation/delete for committed key={} (projectId={}, errorCode={})",
                    claims.objectKey(), claims.projectId(), errorCode);
            return;
        }
        Instant postPutCleanupAt = resolvePostPutCleanupNotBefore(claims);
        if (storageUploadOperationPort != null && claims.operationId() != null) {
            storageUploadOperationPort.compensateFailedUpload(
                    claims.operationId(),
                    new StorageObjectLocator(StorageProviderKind.B2, bucket, claims.objectKey()),
                    errorCode,
                    errorMessage,
                    postPutCleanupAt);
        }
        // Best-effort immediate delete frees oversized/empty bytes now, while the
        // durable cleanup job
        // scheduled strictly after presigned PUT expiry ensures a re-PUT using the
        // still-valid URL is also swept.
        try {
            egressS3StorageService.deleteFile(claims.objectKey());
        } catch (RuntimeException ex) {
            log.warn(
                    "Best-effort immediate B2 delete failed for rejected key={} (durable post-PUT cleanup scheduled at {}): {}",
                    claims.objectKey(), postPutCleanupAt, ex.getMessage());
        }
    }

    private Instant resolvePostPutCleanupNotBefore(PresignedUploadTokenService.UploadTokenClaims claims) {
        if (claims.putExpiresAt() != null) {
            return claims.putExpiresAt().plus(POST_PUT_CLEANUP_GRACE);
        }
        return Instant.now().plus(egressS3StorageService.getPresignPutTtl()).plus(POST_PUT_CLEANUP_GRACE);
    }

    private static String normalizeVideoContentType(String rawContentType) {
        if (rawContentType == null) {
            return "";
        }
        String base = rawContentType.trim().toLowerCase(Locale.ROOT);
        int semi = base.indexOf(';');
        if (semi >= 0) {
            base = base.substring(0, semi).trim();
        }
        return base;
    }

    private static String sanitizeFallbackFileName(String rawName, Long meetingId, String ext) {
        if (rawName != null && !rawName.isBlank()) {
            String cleaned = rawName.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
            if (!cleaned.isBlank() && cleaned.length() <= 180) {
                return cleaned.endsWith(ext) ? cleaned : (cleaned + ext);
            }
        }
        return "meeting_" + meetingId + "_fallback_" + Instant.now().getEpochSecond() + ext;
    }

    private void handleCompletedSnapshot(ProjectMeetingEntity meeting, EgressSnapshot snapshot, Instant now) {
        MeetingRecordingStatus currentStatus = effectiveStatus(meeting);
        if (currentStatus == MeetingRecordingStatus.COMPLETED) {
            repairMissingCloudHistoryOnReplay(meeting, snapshot, now);
            log.debug("Skipping EGRESS_COMPLETE transition for meetingId={} already COMPLETED (history verified)",
                    meeting.getId());
            return;
        }
        if (!RECOVERABLE_COMPLETION_STATUSES.contains(currentStatus)
                || (currentStatus == MeetingRecordingStatus.FAILED && meeting.getRecordingFileId() != null)) {
            log.debug("Skipping EGRESS_COMPLETE for meetingId={} already in status={} with fileId={}",
                    meeting.getId(), currentStatus, meeting.getRecordingFileId());
            return;
        }

        String storageKey = cleanKey(snapshot.filepath());

        if (snapshot.backupStorageUsed() || currentStatus == MeetingRecordingStatus.FAILED) {
            // Verify whether the primary B2 bucket actually received the object before
            // completing/recovering.
            // Distinguish definitive 404 (ObjectNotFoundException) from transient B2
            // network/5xx errors.
            boolean presentOnB2 = false;
            if (!storageKey.isBlank() && egressS3StorageService.isConfigured()) {
                try {
                    EgressS3StorageService.ObjectMetadata meta = egressS3StorageService.headObject(storageKey);
                    presentOnB2 = meta.contentLength() > 0;
                } catch (EgressS3StorageService.ObjectNotFoundException notFound) {
                    presentOnB2 = false;
                } catch (IOException transientErr) {
                    log.warn(
                            "Transient B2 headObject error checking egress {} (key={}); deferring transition to reconciliation: {}",
                            snapshot.egressId(), storageKey, transientErr.getMessage());
                    return;
                }
            }
            if (!presentOnB2) {
                if (snapshot.backupStorageUsed()) {
                    String msg = buildBackupStorageErrorMessage(snapshot.egressId(), snapshot.error());
                    log.warn("Egress {} completed with backup_storage_used=true and object missing from B2: {}",
                            snapshot.egressId(), msg);
                    Integer backupUpdated = requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                            meeting.getId(),
                            snapshot.egressId(),
                            MeetingRecordingStatus.FAILED,
                            msg,
                            true,
                            now,
                            RECOVERABLE_COMPLETION_STATUSES
                    ));
                    if (backupUpdated != null && backupUpdated > 0) {
                        metrics.recordEgressRecording("cloud", "backup_storage");
                    }
                }
                return;
            }
        }

        if (storageKey.isBlank()) {
            requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.FAILED,
                    "LiveKit completed egress without an output file path",
                    snapshot.backupStorageUsed(),
                    now,
                    IN_FLIGHT_STATUSES));
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

        ProjectFileEntity recordingFile = findOrCreateRecordingFile(meeting, snapshot.egressId(), storageKey,
                Math.max(0L, fileSize), now);
        Long durationMs = snapshot.durationNanos() > 0 ? Math.max(1L, snapshot.durationNanos() / 1_000_000L) : null;

        Integer updated = requiresNewTx.execute(tx -> {
            int rows = meetingRepository.transitionRecordingCompleted(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.COMPLETED,
                    recordingFile.getId(),
                    false,
                    now,
                    RECOVERABLE_COMPLETION_STATUSES);
            upsertCompletedServerClipInCurrentTx(
                    meeting,
                    recordingFile,
                    meeting.getHostId(),
                    MeetingRecordingEntity.Origin.CLOUD_EGRESS,
                    MeetingRecordingEntity.Coverage.CLOUD_COMPOSITE,
                    durationMs,
                    "egress:" + snapshot.egressId(),
                    now);
            return rows;
        });
        if (updated != null && updated > 0) {
            metrics.recordEgressRecording("cloud", "completed");
        }

        log.info("Applied EGRESS_COMPLETE: meetingId={}, egressId={}, fileId={}, rowsUpdated={}",
                meeting.getId(), snapshot.egressId(), recordingFile.getId(), updated);
    }

    private void repairMissingCloudHistoryOnReplay(ProjectMeetingEntity meeting, EgressSnapshot snapshot, Instant now) {
        if (meetingRecordingRepository == null) {
            return;
        }
        Optional<ProjectFileEntity> fileOpt = projectFileRepository.findBySourceEgressId(snapshot.egressId());
        if (fileOpt.isEmpty() && meeting.getRecordingFileId() != null
                && Objects.equals(meeting.getEgressId(), snapshot.egressId())) {
            fileOpt = projectFileRepository.findById(meeting.getRecordingFileId());
        }
        if (fileOpt.isEmpty()) {
            return;
        }
        ProjectFileEntity file = fileOpt.get();
        Long durationMs = snapshot.durationNanos() > 0 ? Math.max(1L, snapshot.durationNanos() / 1_000_000L) : null;
        try {
            requiresNewTx.executeWithoutResult(tx -> upsertCompletedServerClipInCurrentTx(
                    meeting,
                    file,
                    meeting.getHostId(),
                    MeetingRecordingEntity.Origin.CLOUD_EGRESS,
                    MeetingRecordingEntity.Coverage.CLOUD_COMPOSITE,
                    durationMs,
                    "egress:" + snapshot.egressId(),
                    now));
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("Idempotent cloud history repair already committed for meetingId={}, fileId={}",
                    meeting.getId(), file.getId());
        }
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

        Integer updated = requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                meeting.getId(),
                snapshot.egressId(),
                MeetingRecordingStatus.FAILED,
                errorMessage,
                snapshot.backupStorageUsed(),
                now,
                IN_FLIGHT_STATUSES
        ));
        if (updated != null && updated > 0) {
            metrics.recordEgressRecording("cloud", snapshot.backupStorageUsed() ? "backup_storage" : "failed");
        }
    }

    /**
     * Enforces idempotent file creation backed by DB UNIQUE index on
     * project_files(source_egress_id).
     * Uses REQUIRES_NEW so a concurrent unique-violation never poisons the outer
     * transaction.
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
        String bucket = egressS3StorageService.getBucket().isBlank() ? "b2-recordings"
                : egressS3StorageService.getBucket();
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
            log.info("Concurrent Egress file insert detected for egressId={}, reusing existing ProjectFileEntity",
                    egressId);
            return projectFileRepository.findBySourceEgressId(egressId)
                    .orElseThrow(() -> raceLost);
        }
    }

    private void upsertFallbackRecordingHistoryInCurrentTx(
            ProjectMeetingEntity meeting,
            ProjectFileEntity file,
            Long userId,
            String logicalKey,
            CompleteRecordingUploadRequest request,
            Instant now) {
        MeetingRecordingEntity.Origin origin = request.origin() == MeetingRecordingEntity.Origin.IMPORT
                ? MeetingRecordingEntity.Origin.IMPORT
                : MeetingRecordingEntity.Origin.BROWSER;
        MeetingRecordingEntity.Coverage coverage;
        if (origin == MeetingRecordingEntity.Origin.IMPORT) {
            coverage = MeetingRecordingEntity.Coverage.IMPORTED_FILE;
        } else if (request.coverage() != null
                && request.coverage() != MeetingRecordingEntity.Coverage.IMPORTED_FILE
                && request.coverage() != MeetingRecordingEntity.Coverage.CLOUD_COMPOSITE) {
            coverage = request.coverage();
        } else {
            coverage = MeetingRecordingEntity.Coverage.SCREEN_ONLY;
        }
        Long durationMs = request.durationSeconds() != null && request.durationSeconds() > 0
                ? request.durationSeconds() * 1000L
                : null;
        upsertCompletedServerClipInCurrentTx(
                meeting, file, userId, origin, coverage, durationMs, logicalKey, now);
    }

    private static String normalizeClientRecordingId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128 || !trimmed.matches("[a-zA-Z0-9._:-]+")) {
            return null;
        }
        return trimmed;
    }

    private static String fallbackLogicalKey(Long userId, String clientRecordingId, String objectKey) {
        if (clientRecordingId != null) {
            return "client:" + userId + ":" + clientRecordingId;
        }
        return "b2-fallback:" + objectKey;
    }

    private void upsertCompletedServerClipInCurrentTx(
            ProjectMeetingEntity meeting,
            ProjectFileEntity file,
            Long recorderId,
            MeetingRecordingEntity.Origin origin,
            MeetingRecordingEntity.Coverage coverage,
            Long durationMs,
            String logicalKey,
            Instant now) {
        if (meetingRecordingRepository == null || file == null || file.getId() == null) {
            return;
        }
        UUID clipId = deterministicClipId(meeting.getProjectId(), meeting.getId(), logicalKey);
        Optional<MeetingRecordingEntity> existingById = meetingRecordingRepository.findById(clipId);
        if (existingById.isPresent()) {
            Long existingFileId = existingById.get().getProjectFileId();
            if (existingFileId != null && !Objects.equals(existingFileId, file.getId())) {
                throw new DuplicateLogicalClipException(existingFileId);
            }
            return;
        }
        if (meetingRecordingRepository.findByProjectFileId(file.getId()).isPresent()) {
            return;
        }
        meetingRecordingRepository.saveAndFlush(MeetingRecordingEntity.builder()
                .id(clipId)
                .meetingId(meeting.getId())
                .projectId(meeting.getProjectId())
                .recorderId(recorderId)
                .projectFileId(file.getId())
                .origin(origin)
                .coverage(coverage)
                .state(MeetingRecordingEntity.State.READY)
                .durationMs(durationMs)
                .byteLength(file.getFileSize())
                .mimeType(file.getContentType())
                .createdAt(now)
                .build());
    }

    private static UUID deterministicClipId(Long projectId, Long meetingId, String logicalKey) {
        return UUID.nameUUIDFromBytes(
                ("meeting-recording:" + projectId + ":" + meetingId + ":" + logicalKey)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        return "recordings/project-" + projectId + "/meeting-" + meetingId + "-" + Instant.now().getEpochSecond()
                + ".mp4";
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

    private static String truncateError(String msg) {
        if (msg == null || msg.isBlank()) {
            return "Cloud recording error";
        }
        return msg.length() <= 1000 ? msg : msg.substring(0, 1000);
    }

    private static String buildBackupStorageErrorMessage(String egressId, String rawError) {
        String detail = (rawError != null && !rawError.isBlank()) ? (" (" + rawError + ")") : "";
        return "Upload to primary storage failed; recording is stored in LiveKit Cloud backup storage (~7-day retention). "
                + "Recover via LiveKit Cloud Dashboard / Support using Egress ID: " + egressId + detail;
    }
}
