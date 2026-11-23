package com.taskpilot.projects.meetings.service;

import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.config.security.FileStreamTokenService;
import com.taskpilot.infrastructure.config.security.PresignedUploadTokenService;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.observability.TaskPilotMetrics;
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
            MeetingRecordingStatus.RECORDING
    );

    private static final List<MeetingRecordingStatus> IN_FLIGHT_STATUSES = List.of(
            MeetingRecordingStatus.IDLE,
            MeetingRecordingStatus.STARTING,
            MeetingRecordingStatus.RECORDING,
            MeetingRecordingStatus.PROCESSING
    );

    private static final List<MeetingRecordingStatus> RECOVERABLE_COMPLETION_STATUSES = List.of(
            MeetingRecordingStatus.IDLE,
            MeetingRecordingStatus.STARTING,
            MeetingRecordingStatus.RECORDING,
            MeetingRecordingStatus.PROCESSING,
            MeetingRecordingStatus.FAILED
    );

    private static final Map<String, String> ALLOWED_FALLBACK_CONTENT_TYPES = Map.of(
            "video/webm", ".webm",
            "video/mp4", ".mp4",
            "video/x-matroska", ".mkv"
    );

    private final ProjectMeetingRepository meetingRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMeetingService projectMeetingService;
    private final LiveKitEgressClientAdapter egressClientAdapter;
    private final EgressS3StorageService egressS3StorageService;
    private final FileStreamTokenService fileStreamTokenService;
    private final PresignedUploadTokenService presignedUploadTokenService;
    private final UserIdentityPort userIdentityPort;
    private final TransactionTemplate requiresNewTx;
    private final boolean gdriveArchiveEnabled;
    private final long maxFallbackFileSizeBytes;
    private final long uploadTokenGraceMinutes;
    private TaskPilotMetrics metrics = TaskPilotMetrics.noop();

    @Autowired(required = false)
    public void setMetrics(TaskPilotMetrics metrics) {
        this.metrics = metrics != null ? metrics : TaskPilotMetrics.noop();
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
            UserIdentityPort userIdentityPort,
            PlatformTransactionManager transactionManager,
            boolean gdriveArchiveEnabled,
            long maxFallbackFileSizeBytes) {
        this(meetingRepository, projectFileRepository, projectMemberRepository, projectSecurityService,
                projectMeetingService, egressClientAdapter, egressS3StorageService, fileStreamTokenService,
                presignedUploadTokenService, userIdentityPort, transactionManager,
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
            UserIdentityPort userIdentityPort,
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
        this.userIdentityPort = userIdentityPort;
        this.gdriveArchiveEnabled = gdriveArchiveEnabled;
        this.maxFallbackFileSizeBytes = Math.max(1024L * 1024L, maxFallbackFileSizeBytes);
        this.uploadTokenGraceMinutes = Math.max(0L, uploadTokenGraceMinutes);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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

        // Atomic claim in a short transaction BEFORE remote LiveKit RPC so concurrent requests cannot spawn duplicate Egress jobs.
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
                    egressS3StorageService
            );
        } catch (Exception e) {
            startObs.error(e);
            metrics.recordEgressRecording("cloud", "start_failed");
            requiresNewTx.execute(tx -> meetingRepository.releaseFailedStartClaim(
                    meetingId,
                    truncateError("Failed to start cloud recording: " + e.getMessage()),
                    Instant.now()
            ));
            log.error("Failed to start LiveKit Egress for meeting {}: {}", meetingId, e.getMessage(), e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY.value(),
                    "Failed to start cloud recording: " + e.getMessage());
        } finally {
            startObs.stop();
        }

        MeetingRecordingStatus nextStatus = "EGRESS_ACTIVE".equals(snapshot.status())
                ? MeetingRecordingStatus.RECORDING
                : MeetingRecordingStatus.STARTING;
        requiresNewTx.execute(tx -> meetingRepository.attachStartedEgress(
                meetingId,
                snapshot.egressId(),
                nextStatus,
                Instant.now()
        ));
        metrics.recordEgressRecording("cloud", "started");

        log.info("Started LiveKit RoomComposite Egress: meetingId={}, room={}, egressId={}, targetKey={}",
                meetingId, meeting.getRoomName(), snapshot.egressId(), filepath);

        return projectMeetingService.getMeetingById(projectId, meetingId, userEmail);
    }

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
            requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    egressId,
                    MeetingRecordingStatus.PROCESSING,
                    null,
                    snapshot.backupStorageUsed(),
                    Instant.now(),
                    ACTIVE_EGRESS_STATUSES
            ));
        }

        return projectMeetingService.getMeetingById(projectId, meetingId, userEmail);
    }

    /**
     * Best-effort stop invoked when the host ends the meeting so LiveKit Egress quota is not leaked.
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
                        ACTIVE_EGRESS_STATUSES
                ));
            }
            log.info("Auto-stopped LiveKit Egress {} on endMeeting for meeting {}", meeting.getEgressId(), meeting.getId());
        } catch (Exception e) {
            log.warn("Best-effort auto-stop Egress {} on endMeeting failed (reconciliation job will sync): {}",
                    meeting.getEgressId(), e.getMessage());
        }
    }

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

    public void markMissingEgressFailed(Long meetingId, String egressId, String reason) {
        requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                meetingId,
                egressId,
                MeetingRecordingStatus.FAILED,
                truncateError(reason),
                false,
                Instant.now(),
                IN_FLIGHT_STATUSES
        ));
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
                Instant.now()
        ));
        if (attached != null && attached > 0) {
            applyEgressSnapshot(snapshot);
        }
    }

    public void releaseStaleStartingClaim(Long meetingId, String reason) {
        requiresNewTx.execute(tx -> meetingRepository.releaseFailedStartClaim(
                meetingId,
                truncateError(reason),
                Instant.now()
        ));
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
        });
    }

    /**
     * Race-safe, idempotent state transition shared by webhooks, startup/periodic reconciliation,
     * stopRecording, and manual sync. Uses explicit {@code requiresNewTx} for all modifying JPQL
     * queries so internal callers never bypass Spring's transactional proxy and remote B2 I/O never
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
                    List.of(MeetingRecordingStatus.IDLE)
            ));
            case "EGRESS_ACTIVE" -> requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.RECORDING,
                    null,
                    snapshot.backupStorageUsed(),
                    now,
                    List.of(MeetingRecordingStatus.IDLE, MeetingRecordingStatus.STARTING)
            ));
            case "EGRESS_ENDING" -> requiresNewTx.execute(tx -> meetingRepository.transitionRecordingStatus(
                    meeting.getId(),
                    snapshot.egressId(),
                    MeetingRecordingStatus.PROCESSING,
                    null,
                    snapshot.backupStorageUsed(),
                    now,
                    List.of(MeetingRecordingStatus.STARTING, MeetingRecordingStatus.RECORDING)
            ));
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

    /**
     * Tier 3 (client fallback): issues a short-lived Backblaze B2 presigned PUT URL locked to
     * a server-generated object key under {@code recordings/{projectId}/{meetingId}/} and exact Content-Type.
     */
    @Transactional(readOnly = true)
    public PresignRecordingUploadResponse presignFallbackUpload(
            Long projectId,
            Long meetingId,
            String userEmail,
            PresignRecordingUploadRequest request) {
        Long userId = getUserId(userEmail);
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
                    "Cannot start fallback upload while a cloud recording is in progress (status=" + currentStatus + ")");
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

        EgressS3StorageService.PresignedUrlResult presigned =
                egressS3StorageService.generatePresignedPutUrl(objectKey, canonicalContentType);

        // Completion uploadToken outlives the short presigned PUT start URL by uploadTokenGraceMinutes
        // so large recordings (e.g. 1 GB on slow uplinks) never fail at complete-upload after PUT finishes.
        java.time.Duration uploadTokenTtl = egressS3StorageService.getPresignPutTtl()
                .plusMinutes(uploadTokenGraceMinutes);
        String uploadToken = presignedUploadTokenService.issueToken(
                projectId,
                meetingId,
                userId,
                objectKey,
                canonicalContentType,
                safeFileName,
                uploadTokenTtl
        );

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
     * Verifies the server-issued uploadToken, checks the uploaded B2 object via HeadObject,
     * deletes and rejects oversized objects, and idempotently links the recording to the meeting.
     * Runs remote B2 I/O outside any DB transaction and uses unique B2 key lookup + REQUIRES_NEW
     * so retries/replays never create duplicate ProjectFileEntity rows sharing one B2 key.
     */
    public ProjectMeetingResponse completeFallbackUpload(
            Long projectId,
            Long meetingId,
            String userEmail,
            CompleteRecordingUploadRequest request) {
        Long userId = getUserId(userEmail);
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
                    "Cannot link fallback recording while a cloud recording is in progress (status=" + currentStatus + ")");
        }

        PresignedUploadTokenService.UploadTokenClaims claims = presignedUploadTokenService
                .verifyToken(request.uploadToken(), projectId, meetingId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Invalid or expired recording upload token"));

        if (!egressS3StorageService.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Backblaze B2 recording storage is not configured");
        }

        // Idempotency guard: if this uploadToken/objectKey was already registered, reuse existing row cleanly.
        Optional<ProjectFileEntity> existingFile =
                projectFileRepository.findByProjectIdAndStorageKey(projectId, claims.objectKey());
        if (existingFile.isPresent()) {
            ProjectFileEntity reused = existingFile.get();
            Integer linked = requiresNewTx.execute(tx ->
                    meetingRepository.linkFallbackRecording(meetingId, reused.getId(), Instant.now()));
            if (linked == null || linked == 0) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Cannot link fallback recording while a cloud recording is in progress");
            }
            log.info("Idempotent completeFallbackUpload reused existing fileId={} for key={}", reused.getId(), claims.objectKey());
            return projectMeetingService.getMeetingById(projectId, meetingId, userEmail);
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
            egressS3StorageService.deleteFile(claims.objectKey());
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Uploaded recording file is empty and was removed");
        }

        if (meta.contentLength() > maxFallbackFileSizeBytes) {
            egressS3StorageService.deleteFile(claims.objectKey());
            log.warn("Deleted oversized client fallback recording from B2: key={}, size={}, limit={}",
                    claims.objectKey(), meta.contentLength(), maxFallbackFileSizeBytes);
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "Recording exceeds maximum allowed size of " + (maxFallbackFileSizeBytes / (1024 * 1024)) + " MB");
        }

        Instant now = Instant.now();
        ProjectFileEntity savedFile = findOrCreateFallbackFile(meeting, userId, claims, meta.contentLength(), now);

        Integer linked = requiresNewTx.execute(tx ->
                meetingRepository.linkFallbackRecording(meetingId, savedFile.getId(), now));
        if (linked == null || linked == 0) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Cannot link fallback recording because a cloud recording became active");
        }
        metrics.recordEgressRecording("fallback", "completed");

        log.info("Completed fallback B2 recording upload: meetingId={}, fileId={}, key={}, size={}",
                meetingId, savedFile.getId(), claims.objectKey(), meta.contentLength());

        return projectMeetingService.getMeetingById(projectId, meetingId, userEmail);
    }

    private ProjectFileEntity findOrCreateFallbackFile(
            ProjectMeetingEntity meeting,
            Long userId,
            PresignedUploadTokenService.UploadTokenClaims claims,
            long contentLength,
            Instant now) {
        String bucket = egressS3StorageService.getBucket().isBlank() ? "b2-recordings" : egressS3StorageService.getBucket();
        FileArchiveStatus initialArchiveStatus = gdriveArchiveEnabled
                ? FileArchiveStatus.ARCHIVE_PENDING
                : FileArchiveStatus.NONE;

        try {
            return Objects.requireNonNull(requiresNewTx.execute(status -> projectFileRepository.saveAndFlush(
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
                            .build()
            )));
        } catch (DataIntegrityViolationException raceLost) {
            log.info("Concurrent fallback upload completion detected for key={}, reusing existing ProjectFileEntity",
                    claims.objectKey());
            return projectFileRepository.findByProjectIdAndStorageKey(meeting.getProjectId(), claims.objectKey())
                    .orElseThrow(() -> raceLost);
        }
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
        if (!RECOVERABLE_COMPLETION_STATUSES.contains(currentStatus)
                || (currentStatus == MeetingRecordingStatus.FAILED && meeting.getRecordingFileId() != null)) {
            log.debug("Skipping EGRESS_COMPLETE for meetingId={} already in status={} with fileId={}",
                    meeting.getId(), currentStatus, meeting.getRecordingFileId());
            return;
        }

        String storageKey = cleanKey(snapshot.filepath());

        if (snapshot.backupStorageUsed() || currentStatus == MeetingRecordingStatus.FAILED) {
            // Verify whether the primary B2 bucket actually received the object before completing/recovering.
            // Distinguish definitive 404 (ObjectNotFoundException) from transient B2 network/5xx errors.
            boolean presentOnB2 = false;
            if (!storageKey.isBlank() && egressS3StorageService.isConfigured()) {
                try {
                    EgressS3StorageService.ObjectMetadata meta = egressS3StorageService.headObject(storageKey);
                    presentOnB2 = meta.contentLength() > 0;
                } catch (EgressS3StorageService.ObjectNotFoundException notFound) {
                    presentOnB2 = false;
                } catch (IOException transientErr) {
                    log.warn("Transient B2 headObject error checking egress {} (key={}); deferring transition to reconciliation: {}",
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
                    IN_FLIGHT_STATUSES
            ));
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

        Integer updated = requiresNewTx.execute(tx -> meetingRepository.transitionRecordingCompleted(
                meeting.getId(),
                snapshot.egressId(),
                MeetingRecordingStatus.COMPLETED,
                recordingFile.getId(),
                false,
                now,
                RECOVERABLE_COMPLETION_STATUSES
        ));
        if (updated != null && updated > 0) {
            metrics.recordEgressRecording("cloud", "completed");
        }

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
