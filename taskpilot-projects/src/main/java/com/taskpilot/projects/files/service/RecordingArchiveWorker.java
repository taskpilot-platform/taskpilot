package com.taskpilot.projects.files.service;

import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.observability.TaskPilotMetrics;
import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.FileArchiveStatus;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Tier 2 Hot/Cold Storage Archive Worker:
 * Moves aged recordings from Backblaze B2 (hot) to private Google Drive 5TB (cold) via
 * chunked resumable streaming with on-the-fly MD5 verification.
 *
 * Guarantees:
 * 1. Zero full-file RAM buffering (streams directly from B2 InputStream into Google Drive MediaHttpUploader).
 * 2. Private Drive files only (no public "anyone" permission).
 * 3. Atomic DB claim (ARCHIVE_PENDING/ARCHIVE_FAILED -> ARCHIVING) + stale lock recovery after restart.
 * 4. Strict integrity gate: verifies BOTH size AND MD5 checksum match before committing DB and deleting from B2.
 * 5. DB switch commits BEFORE deleting from B2 so no recording is ever lost on mid-operation crash.
 */
@Slf4j
@Service
public class RecordingArchiveWorker {

    private static final List<FileArchiveStatus> CLAIMABLE_STATUSES = List.of(
            FileArchiveStatus.ARCHIVE_PENDING,
            FileArchiveStatus.ARCHIVE_FAILED
    );
    private static final List<FileArchiveStatus> MANUAL_CLAIMABLE_STATUSES = List.of(
            FileArchiveStatus.NONE,
            FileArchiveStatus.ARCHIVE_PENDING,
            FileArchiveStatus.ARCHIVE_FAILED
    );

    private final ProjectFileRepository projectFileRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final UserIdentityPort userIdentityPort;
    private final EgressS3StorageService egressS3StorageService;
    private final GoogleDriveService googleDriveService;
    private final TransactionTemplate requiresNewTx;

    private final boolean enabled;
    private final long archiveAfterDays;
    private final int maxRetries;
    private final long retryCooldownMinutes;
    private final int chunkSizeBytes;
    private final long staleArchivingMinutes;
    private TaskPilotMetrics metrics = TaskPilotMetrics.noop();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(TaskPilotMetrics metrics) {
        this.metrics = metrics != null ? metrics : TaskPilotMetrics.noop();
    }

    public RecordingArchiveWorker(
            ProjectFileRepository projectFileRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            UserIdentityPort userIdentityPort,
            EgressS3StorageService egressS3StorageService,
            GoogleDriveService googleDriveService,
            PlatformTransactionManager transactionManager,
            boolean enabled,
            long archiveAfterDays,
            int maxRetries,
            int chunkSizeBytes,
            long staleArchivingMinutes) {
        this(projectFileRepository, projectMemberRepository, projectSecurityService, userIdentityPort,
                egressS3StorageService, googleDriveService, transactionManager,
                enabled, archiveAfterDays, maxRetries, 15L, chunkSizeBytes, staleArchivingMinutes);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RecordingArchiveWorker(
            ProjectFileRepository projectFileRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            UserIdentityPort userIdentityPort,
            EgressS3StorageService egressS3StorageService,
            GoogleDriveService googleDriveService,
            PlatformTransactionManager transactionManager,
            @Value("${egress.archive.gdrive.enabled:true}") boolean enabled,
            @Value("${egress.archive.gdrive.after-days:3}") long archiveAfterDays,
            @Value("${egress.archive.gdrive.max-retries:5}") int maxRetries,
            @Value("${egress.archive.gdrive.retry-cooldown-minutes:15}") long retryCooldownMinutes,
            @Value("${egress.archive.gdrive.chunk-size-bytes:4194304}") int chunkSizeBytes,
            @Value("${egress.archive.gdrive.stale-archiving-minutes:30}") long staleArchivingMinutes) {
        this.projectFileRepository = projectFileRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.userIdentityPort = userIdentityPort;
        this.egressS3StorageService = egressS3StorageService;
        this.googleDriveService = googleDriveService;
        this.enabled = enabled;
        this.archiveAfterDays = Math.max(0L, archiveAfterDays);
        this.maxRetries = Math.max(1, maxRetries);
        this.retryCooldownMinutes = Math.max(1L, retryCooldownMinutes);
        this.chunkSizeBytes = chunkSizeBytes;
        this.staleArchivingMinutes = Math.max(1L, staleArchivingMinutes);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleLocksOnStartup() {
        if (!enabled) {
            return;
        }
        try {
            int recovered = resetStaleArchivingLocks();
            if (recovered > 0) {
                log.info("Recovered {} stale ARCHIVING file(s) back to ARCHIVE_PENDING on startup", recovered);
            }
        } catch (Exception e) {
            log.warn("Startup stale archive recovery skipped safely: {}", e.getMessage());
        }
    }

    @Scheduled(
            fixedDelayString = "${egress.archive.gdrive.poll-interval-ms:300000}",
            initialDelayString = "${egress.archive.gdrive.initial-delay-ms:60000}"
    )
    public void runScheduledArchiveSweep() {
        processEligibleArchives(10);
    }

    public int processEligibleArchives(int batchSize) {
        if (!enabled || !egressS3StorageService.isConfigured() || !googleDriveService.isConfigured()) {
            return 0;
        }

        resetStaleArchivingLocks();

        Instant now = Instant.now();
        Instant cutoff = archiveAfterDays == 0L
                ? now
                : now.minus(Duration.ofDays(archiveAfterDays));
        Instant retryCutoff = now.minus(Duration.ofMinutes(retryCooldownMinutes));

        List<ProjectFileEntity> candidates = projectFileRepository.findEligibleForArchive(
                CLAIMABLE_STATUSES,
                maxRetries,
                cutoff,
                retryCutoff,
                PageRequest.of(0, Math.max(1, batchSize))
        );

        int archivedCount = 0;
        for (ProjectFileEntity file : candidates) {
            if (!isRetryBackoffElapsed(file, now)) {
                continue;
            }
            if (archiveSingleFile(file, CLAIMABLE_STATUSES)) {
                archivedCount++;
            }
        }
        return archivedCount;
    }

    boolean isRetryBackoffElapsed(ProjectFileEntity file, Instant now) {
        if (file.getArchiveStatus() != FileArchiveStatus.ARCHIVE_FAILED) {
            return true;
        }
        int retries = file.getArchiveRetryCount() != null ? file.getArchiveRetryCount() : 0;
        if (retries <= 1 || file.getUpdatedAt() == null) {
            return true;
        }
        // Exponential backoff: base * 2^(retries - 1), capped at 24 hours
        long backoffMinutes = Math.min(24L * 60L, retryCooldownMinutes << Math.min(6, retries - 1));
        return !file.getUpdatedAt().plus(Duration.ofMinutes(backoffMinutes)).isAfter(now);
    }

    /**
     * Allows a project manager or file uploader to immediately archive a hot B2 file to cold Google Drive 5TB
     * (useful for immediate cold tiering or live thesis demonstration without waiting N days).
     */
    public boolean archiveFileNow(Long projectId, Long fileId, String userEmail) {
        Long userId = userIdentityPort.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"))
                .id();
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectFileEntity entity = projectFileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "File not found"));

        boolean isUploader = Objects.equals(entity.getUploaderId(), userId);
        if (!isUploader) {
            ProjectMemberEntity member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(), "Not a member"));
            if (member.getRole() != MemberRole.MANAGER) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "Only the uploader or project manager can trigger immediate cold archival");
            }
        }

        if (!"b2".equalsIgnoreCase(entity.getStorageProvider())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "File is not currently stored on Backblaze B2 (provider=" + entity.getStorageProvider() + ")");
        }
        if (!egressS3StorageService.isConfigured() || !googleDriveService.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Backblaze B2 and Google Drive 5TB must both be configured to archive");
        }

        return archiveSingleFile(entity, MANUAL_CLAIMABLE_STATUSES);
    }

    boolean archiveSingleFile(ProjectFileEntity file, List<FileArchiveStatus> claimableStatuses) {
        return Boolean.TRUE.equals(metrics.observe(
                "taskpilot.archive.transfer",
                "storage",
                "b2_to_gdrive",
                () -> doArchiveSingleFile(file, claimableStatuses)
        ));
    }

    private boolean doArchiveSingleFile(ProjectFileEntity file, List<FileArchiveStatus> claimableStatuses) {
        Long fileId = file.getId();
        String b2Key = file.getStorageKey();

        Integer claimed = requiresNewTx.execute(tx ->
                projectFileRepository.claimForArchiving(fileId, claimableStatuses, Instant.now())
        );
        if (claimed == null || claimed == 0) {
            log.debug("Skipping archive for fileId={}: already claimed or no longer eligible", fileId);
            return false;
        }

        long startedAt = System.currentTimeMillis();
        String uploadedDriveFileId = null;
        try {
            EgressS3StorageService.ObjectMetadata b2Meta = egressS3StorageService.headObject(b2Key);
            long expectedSize = b2Meta.contentLength();
            if (expectedSize <= 0) {
                throw new IllegalStateException("Source object on Backblaze B2 has zero size: key=" + b2Key);
            }

            GoogleDriveService.StreamedUploadResult uploadResult;
            try (InputStream b2Stream = egressS3StorageService.downloadFile(b2Key)) {
                uploadResult = googleDriveService.uploadPrivateStreamWithMd5(
                        b2Stream,
                        file.getOriginalName(),
                        file.getContentType() != null ? file.getContentType() : b2Meta.contentType(),
                        expectedSize,
                        null,
                        chunkSizeBytes
                );
            }

            if (uploadResult == null || uploadResult.driveFile() == null
                    || uploadResult.driveFile().getFileId() == null
                    || uploadResult.driveFile().getFileId().isBlank()) {
                throw new IllegalStateException("Google Drive upload returned empty file metadata");
            }

            uploadedDriveFileId = uploadResult.driveFile().getFileId();
            Long driveSize = uploadResult.driveFile().getSize();
            String driveMd5 = uploadResult.driveFile().getMd5Checksum();
            String computedMd5 = uploadResult.computedMd5Hex();

            boolean sizeMatches = uploadResult.streamedBytes() == expectedSize
                    && driveSize != null
                    && driveSize == expectedSize;
            boolean md5Matches = driveMd5 != null
                    && !driveMd5.isBlank()
                    && computedMd5 != null
                    && driveMd5.equalsIgnoreCase(computedMd5);

            if (!sizeMatches || !md5Matches) {
                String mismatch = String.format(
                        "Integrity verification failed before B2 delete (expectedSize=%d, streamed=%d, driveSize=%s, computedMd5=%s, driveMd5=%s)",
                        expectedSize, uploadResult.streamedBytes(), driveSize, computedMd5, driveMd5
                );
                log.error("FileId={} archive verification mismatch: {}", fileId, mismatch);
                safeDeleteDriveOrphan(uploadedDriveFileId);
                recordArchiveFailure(fileId, mismatch);
                metrics.recordArchiveTransfer("integrity_mismatch", 0L, System.currentTimeMillis() - startedAt);
                return false;
            }

            final String verifiedDriveId = uploadedDriveFileId;
            Integer updated = requiresNewTx.execute(tx ->
                    projectFileRepository.completeArchiveToGoogleDrive(
                            fileId,
                            verifiedDriveId,
                            expectedSize,
                            computedMd5,
                            Instant.now()
                    )
            );
            if (updated == null || updated == 0) {
                log.warn("FileId={} DB completion update affected 0 rows; keeping B2 object intact", fileId);
                safeDeleteDriveOrphan(verifiedDriveId);
                metrics.recordArchiveTransfer("failed", 0L, System.currentTimeMillis() - startedAt);
                return false;
            }

            // Only delete from Backblaze B2 AFTER MD5+size verification AND DB commit succeeded.
            // Once committed, the Drive copy is live and must NEVER be deleted if B2 cleanup throws.
            uploadedDriveFileId = null;
            try {
                egressS3StorageService.deleteFileOrThrow(b2Key);
            } catch (Exception b2CleanupEx) {
                metrics.recordArchiveTransfer("b2_cleanup_failed", 0L, -1L);
                log.warn("Archived fileId={} to private Google Drive ({}), but failed to delete hot B2 copy ({}): {}",
                        fileId, verifiedDriveId, b2Key, b2CleanupEx.getMessage());
            }

            metrics.recordArchiveTransfer("success", expectedSize, System.currentTimeMillis() - startedAt);
            log.info("Archived fileId={} from B2 ({}) to private Google Drive ({}): size={}, md5={}",
                    fileId, b2Key, verifiedDriveId, expectedSize, computedMd5);
            return true;
        } catch (Exception e) {
            log.error("Failed archiving fileId={} from B2 to Google Drive: {}", fileId, e.getMessage(), e);
            if (uploadedDriveFileId != null) {
                safeDeleteDriveOrphan(uploadedDriveFileId);
            }
            recordArchiveFailure(fileId, truncateError(e.getMessage()));
            metrics.recordArchiveTransfer("failed", 0L, System.currentTimeMillis() - startedAt);
            return false;
        }
    }

    int resetStaleArchivingLocks() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(Duration.ofMinutes(staleArchivingMinutes));
        Integer count = requiresNewTx.execute(tx -> projectFileRepository.resetStaleArchiving(staleBefore, now));
        return count != null ? count : 0;
    }

    private void recordArchiveFailure(Long fileId, String errorMessage) {
        try {
            requiresNewTx.execute(tx ->
                    projectFileRepository.markArchiveFailed(fileId, truncateError(errorMessage), Instant.now())
            );
        } catch (Exception dbEx) {
            log.error("Failed to persist ARCHIVE_FAILED status for fileId={}: {}", fileId, dbEx.getMessage());
        }
    }

    private void safeDeleteDriveOrphan(String driveFileId) {
        try {
            googleDriveService.deleteFile(driveFileId);
        } catch (Exception cleanupEx) {
            log.warn("Could not clean up orphan Google Drive file {}: {}", driveFileId, cleanupEx.getMessage());
        }
    }

    private static String truncateError(String msg) {
        if (msg == null || msg.isBlank()) {
            return "Archive transfer failed";
        }
        return msg.length() <= 1000 ? msg : msg.substring(0, 1000);
    }
}
