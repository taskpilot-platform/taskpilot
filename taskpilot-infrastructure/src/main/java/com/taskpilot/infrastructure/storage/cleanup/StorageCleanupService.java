package com.taskpilot.infrastructure.storage.cleanup;

import com.taskpilot.contracts.storage.StorageCleanupJobSummary;
import com.taskpilot.contracts.storage.StorageCleanupPort;
import com.taskpilot.contracts.storage.StorageCleanupRequest;
import com.taskpilot.contracts.storage.StorageCleanupStatus;
import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.infrastructure.storage.range.StorageDeleteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable after-commit storage cleanup ledger, claim/lease coordinator, and retry/dead-letter engine.
 */
@Slf4j
@Service
public class StorageCleanupService implements StorageCleanupPort {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_PAIR_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+\\S+|(token|secret|password|key|signature)=[^&\\s]+)");

    private final StorageCleanupJobRepository jobRepository;
    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;
    private final GoogleDriveService googleDriveService;
    private EgressS3StorageService egressS3StorageService;

    @Autowired(required = false)
    public void setEgressS3StorageService(EgressS3StorageService egressS3StorageService) {
        this.egressS3StorageService = egressS3StorageService;
    }
    private final TransactionTemplate requiresNewTx;
    private final int maxAttempts;
    private final long baseBackoffSeconds;
    private final Duration defaultLeaseDuration;

    @Autowired
    public StorageCleanupService(
            StorageCleanupJobRepository jobRepository,
            JdbcTemplate jdbcTemplate,
            StorageService storageService,
            @Autowired(required = false) GoogleDriveService googleDriveService,
            @Autowired(required = false) PlatformTransactionManager transactionManager,
            @Value("${taskpilot.storage.cleanup.max-attempts:6}") int maxAttempts,
            @Value("${taskpilot.storage.cleanup.base-backoff-seconds:30}") long baseBackoffSeconds,
            @Value("${taskpilot.storage.cleanup.lease-seconds:120}") long leaseSeconds) {
        this.jobRepository = jobRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.storageService = storageService;
        this.googleDriveService = googleDriveService;
        if (transactionManager != null) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = tx;
        } else {
            this.requiresNewTx = null;
        }
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffSeconds = Math.max(1L, baseBackoffSeconds);
        this.defaultLeaseDuration = Duration.ofSeconds(Math.max(5L, leaseSeconds));
    }

    @Override
    public void enqueueCleanup(StorageCleanupRequest request) {
        if (request == null) {
            return;
        }
        enqueueCleanupBatch(List.of(request));
    }

    @Override
    public void enqueueCleanupBatch(List<StorageCleanupRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        for (StorageCleanupRequest request : requests) {
            if (request == null || request.locator() == null) {
                continue;
            }
            StorageObjectLocator locator = request.locator();
            String dedupeKey = sha256Hex(locator.dedupeKey());
            jdbcTemplate.update("""
                    INSERT INTO public.storage_cleanup_jobs (
                        dedupe_key, provider, bucket, object_key, operation,
                        source_type, source_id, project_id, upload_operation_id,
                        status, attempts, max_attempts, next_attempt_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'DELETE_OBJECT', ?, ?, ?, ?, 'PENDING', 0, ?, NOW(), NOW(), NOW())
                    ON CONFLICT (dedupe_key) WHERE status IN ('PENDING', 'IN_PROGRESS')
                    DO NOTHING
                    """,
                    dedupeKey,
                    locator.provider().name(),
                    locator.bucket(),
                    locator.objectKey(),
                    request.sourceType(),
                    request.sourceId(),
                    request.projectId(),
                    request.uploadOperationId(),
                    maxAttempts
            );
        }
    }

    /**
     * Claims a bounded batch in a short transaction, executes provider deletes OUTSIDE any DB transaction,
     * and applies fenced completion updates in separate short transactions.
     */
    public int processPendingBatch(int batchSize, String workerId) {
        return processPendingBatch(batchSize, workerId, defaultLeaseDuration);
    }

    public int processPendingBatch(int batchSize, String workerId, Duration leaseDuration) {
        int boundedBatch = Math.min(Math.max(batchSize, 1), 100);
        String resolvedWorkerId = (workerId != null && !workerId.isBlank())
                ? workerId.trim()
                : "worker-" + UUID.randomUUID();
        Duration resolvedLease = (leaseDuration != null && !leaseDuration.isNegative() && !leaseDuration.isZero())
                ? leaseDuration
                : defaultLeaseDuration;

        List<StorageCleanupJobSummary> claimed = claimBatch(boundedBatch, resolvedWorkerId, resolvedLease);
        if (claimed.isEmpty()) {
            return 0;
        }

        for (StorageCleanupJobSummary job : claimed) {
            StorageDeleteResult outcome = executeProviderDelete(job);
            completeJobFenced(job, resolvedWorkerId, outcome);
        }
        return claimed.size();
    }

    public List<StorageCleanupJobSummary> claimBatch(int batchSize, String workerId, Duration leaseDuration) {
        long leaseMillis = Math.max(1000L, leaseDuration.toMillis());
        return executeRequiresNew(() -> jdbcTemplate.query("""
                WITH next_jobs AS (
                    SELECT id
                    FROM public.storage_cleanup_jobs
                    WHERE (status = 'PENDING' AND next_attempt_at <= NOW())
                       OR (status = 'IN_PROGRESS' AND lease_until IS NOT NULL AND lease_until < NOW())
                    ORDER BY next_attempt_at ASC, id ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE public.storage_cleanup_jobs j
                SET status = 'IN_PROGRESS',
                    lease_owner = ?,
                    lease_until = NOW() + (? * INTERVAL '1 millisecond'),
                    updated_at = NOW()
                FROM next_jobs
                WHERE j.id = next_jobs.id
                RETURNING j.id, j.dedupe_key, j.provider, j.bucket, j.object_key,
                          j.source_type, j.source_id, j.project_id, j.upload_operation_id,
                          j.status, j.attempts, j.max_attempts, j.next_attempt_at,
                          j.lease_until, j.last_error_code, j.last_error_message,
                          j.completed_at, j.created_at
                """,
                ROW_MAPPER,
                batchSize,
                workerId,
                leaseMillis
        ));
    }

    protected StorageDeleteResult executeProviderDelete(StorageCleanupJobSummary job) {
        try {
            if (job.provider() == StorageProviderKind.B2) {
                if (egressS3StorageService == null || !egressS3StorageService.isConfigured()) {
                    return StorageDeleteResult.retryableFailure("B2_UNAVAILABLE", "B2 storage is unavailable");
                }
                return egressS3StorageService.deleteObject(job.bucket(), job.objectKey());
            }
            if (job.provider() == StorageProviderKind.GOOGLE_DRIVE) {
                if (googleDriveService == null) {
                    return StorageDeleteResult.retryableFailure(
                            "DRIVE_UNAVAILABLE", "GoogleDriveService bean is unavailable");
                }
                return googleDriveService.deleteObject(job.objectKey());
            }
            return storageService.deleteObject(job.bucket(), job.objectKey());
        } catch (Exception ex) {
            return StorageDeleteResult.retryableFailure("PROVIDER_EXCEPTION", sanitizeErrorMessage(ex.getMessage()));
        }
    }

    protected void completeJobFenced(StorageCleanupJobSummary job, String workerId, StorageDeleteResult result) {
        executeRequiresNew(() -> {
            if (result.isSuccess()) {
                int updated = jdbcTemplate.update("""
                        UPDATE public.storage_cleanup_jobs
                        SET status = 'SUCCEEDED',
                            completed_at = NOW(),
                            lease_owner = NULL,
                            lease_until = NULL,
                            last_error_code = NULL,
                            last_error_message = NULL,
                            updated_at = NOW()
                        WHERE id = ?
                          AND status = 'IN_PROGRESS'
                          AND lease_owner = ?
                        """,
                        job.id(),
                        workerId
                );
                if (updated == 0) {
                    log.warn("Skipped completion update for cleanup job {} due to lost lease {}", job.id(), workerId);
                }
                return updated;
            }

            int nextAttempts = job.attempts() + 1;
            String safeCode = truncate(result.errorCode() != null ? result.errorCode() : "DELETE_FAILED", 64);
            String safeMsg = sanitizeErrorMessage(result.safeMessage());

            if (nextAttempts >= job.maxAttempts()) {
                jdbcTemplate.update("""
                        UPDATE public.storage_cleanup_jobs
                        SET status = 'DEAD_LETTER',
                            attempts = ?,
                            lease_owner = NULL,
                            lease_until = NULL,
                            last_error_code = ?,
                            last_error_message = ?,
                            updated_at = NOW()
                        WHERE id = ?
                          AND status = 'IN_PROGRESS'
                          AND lease_owner = ?
                        """,
                        nextAttempts,
                        safeCode,
                        safeMsg,
                        job.id(),
                        workerId
                );
            } else {
                long delaySeconds = computeBackoffSeconds(nextAttempts);
                jdbcTemplate.update("""
                        UPDATE public.storage_cleanup_jobs
                        SET status = 'PENDING',
                            attempts = ?,
                            next_attempt_at = NOW() + (? * INTERVAL '1 second'),
                            lease_owner = NULL,
                            lease_until = NULL,
                            last_error_code = ?,
                            last_error_message = ?,
                            updated_at = NOW()
                        WHERE id = ?
                          AND status = 'IN_PROGRESS'
                          AND lease_owner = ?
                        """,
                        nextAttempts,
                        delaySeconds,
                        safeCode,
                        safeMsg,
                        job.id(),
                        workerId
                );
            }
            return 1;
        });
    }

    @Override
    public boolean retryDeadLetterJob(long jobId) {
        Integer updated = executeRequiresNew(() -> jdbcTemplate.update("""
                UPDATE public.storage_cleanup_jobs
                SET status = 'PENDING',
                    attempts = 0,
                    next_attempt_at = NOW(),
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    completed_at = NULL,
                    updated_at = NOW()
                WHERE id = ?
                  AND status = 'DEAD_LETTER'
                """,
                jobId
        ));
        return updated != null && updated > 0;
    }

    @Override
    public List<StorageCleanupJobSummary> findJobsByProject(long projectId) {
        return jobRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(StorageCleanupService::toSummary)
                .toList();
    }

    public List<StorageCleanupJobSummary> findAllJobs() {
        return jobRepository.findAll().stream()
                .map(StorageCleanupService::toSummary)
                .toList();
    }

    long computeBackoffSeconds(int attemptNumber) {
        int shift = Math.min(Math.max(attemptNumber - 1, 0), 10);
        return Math.min(3600L, baseBackoffSeconds * (1L << shift));
    }

    public static String sanitizeErrorMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String sanitized = URL_PATTERN.matcher(raw).replaceAll("[redacted-url]");
        sanitized = SECRET_PAIR_PATTERN.matcher(sanitized).replaceAll("[redacted]");
        return truncate(sanitized.trim(), 500);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private <T> T executeRequiresNew(java.util.function.Supplier<T> action) {
        if (requiresNewTx == null) {
            return action.get();
        }
        return requiresNewTx.execute(status -> action.get());
    }

    private static StorageCleanupJobSummary toSummary(StorageCleanupJobEntity entity) {
        return new StorageCleanupJobSummary(
                entity.getId(),
                entity.getDedupeKey(),
                entity.getProvider(),
                entity.getBucket(),
                entity.getObjectKey(),
                entity.getSourceType(),
                entity.getSourceId(),
                entity.getProjectId(),
                entity.getUploadOperationId(),
                entity.getStatus(),
                entity.getAttempts(),
                entity.getMaxAttempts(),
                entity.getNextAttemptAt(),
                entity.getLeaseUntil(),
                entity.getLastErrorCode(),
                entity.getLastErrorMessage(),
                entity.getCompletedAt(),
                entity.getCreatedAt()
        );
    }

    private static final RowMapper<StorageCleanupJobSummary> ROW_MAPPER = (rs, rowNum) -> new StorageCleanupJobSummary(
            rs.getLong("id"),
            rs.getString("dedupe_key"),
            StorageProviderKind.valueOf(rs.getString("provider")),
            rs.getString("bucket"),
            rs.getString("object_key"),
            rs.getString("source_type"),
            rs.getString("source_id"),
            rs.getObject("project_id", Long.class),
            rs.getObject("upload_operation_id", UUID.class),
            StorageCleanupStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getInt("max_attempts"),
            toInstant(rs.getTimestamp("next_attempt_at")),
            toInstant(rs.getTimestamp("lease_until")),
            rs.getString("last_error_code"),
            rs.getString("last_error_message"),
            toInstant(rs.getTimestamp("completed_at")),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
