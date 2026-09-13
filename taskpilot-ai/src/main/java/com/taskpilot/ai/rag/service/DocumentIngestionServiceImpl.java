package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.domain.StagedChunk;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentChunkStagingRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.infrastructure.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes document text extraction, deterministic chunking, and embedding generation
 * with a staging architecture (document_chunk_staging) for resumable embedding,
 * optimistic fencing tokens (processing_version), and atomic SELECT FOR UPDATE finalization.
 * Network I/O (S3 and Google Gemini) is executed outside database transactions.
 */
@Slf4j
@Service
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentChunkStagingRepository stagingRepository;
    private final StorageService storageService;
    private final DocumentTextExtractor documentTextExtractor;
    private final DocumentChunker documentChunker;
    private final EmbeddingGateway embeddingGateway;
    private final RagEmbeddingProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public DocumentIngestionServiceImpl(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            DocumentChunkStagingRepository stagingRepository,
            StorageService storageService,
            DocumentTextExtractor documentTextExtractor,
            DocumentChunker documentChunker,
            EmbeddingGateway embeddingGateway,
            RagEmbeddingProperties properties,
            JdbcTemplate jdbcTemplate,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.stagingRepository = stagingRepository;
        this.storageService = storageService;
        this.documentTextExtractor = documentTextExtractor;
        this.documentChunker = documentChunker;
        this.embeddingGateway = embeddingGateway;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    @Override
    public void ingestDocument(Long documentId, int claimedVersion) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }

        // 1. Verify document exists and claimedVersion matches before performing expensive I/O
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        if (document.getStatus() != DocumentStatus.PROCESSING || document.getProcessingVersion() != claimedVersion) {
            log.warn("Ingestion aborted: document {} status is {} (version {}), claimed version was {}",
                    documentId, document.getStatus(), document.getProcessingVersion(), claimedVersion);
            return;
        }

        Long projectId = document.getProjectId();

        try {
            // 2. Fresh extraction vs. Resume semantics (P0 Requirement 4)
            boolean stagingExists = stagingRepository.hasStagedChunks(documentId);

            if (!stagingExists) {
                log.info("Document {} v{}: executing FRESH EXTRACTION workflow", documentId, claimedVersion);
                stagingRepository.deleteStagedChunks(documentId);

                String storageKey = document.getStorageKey();
                String originalFilename = document.getOriginalFilename();
                String contentType = document.getContentType();

                // 2a. Download from S3 outside DB transaction
                String text;
                try (InputStream stream = storageService.downloadFile("documents", storageKey)) {
                    text = documentTextExtractor.extractText(stream, originalFilename, contentType);
                }

                if (text == null || text.isBlank()) {
                    throw new IllegalStateException("Extracted text from document is empty");
                }
                int extractedChars = text.length();

                // 2b. Recursive deterministic chunking
                List<String> textChunks = documentChunker.chunkText(text);
                if (textChunks.isEmpty()) {
                    throw new IllegalStateException("Chunker produced 0 chunks from extracted text");
                }
                int chunkCount = textChunks.size();

                // 2c. Persist all text chunks into staging with embedding = NULL before embedding
                stagingRepository.stageInitialChunks(documentId, textChunks);
                log.info("Staged {} initial text chunks for doc {} v{} (extracted {} chars)",
                        chunkCount, documentId, claimedVersion, extractedChars);
            } else {
                log.info("Document {} v{}: executing RESUME workflow (reusing existing staged chunks)", documentId, claimedVersion);
            }

            // 3. Resumable embedding loop: query batches where embedding IS NULL (P0 Requirement 2, 5, 6, 7, 14)
            int maxBatchSize = properties.getMaxBatchSize();
            while (true) {
                // 3a. Renew worker lease at beginning of every batch (P0 Requirement 7: 3 minutes)
                int renewed = jdbcTemplate.update("""
                        UPDATE documents
                        SET lease_until = NOW() + INTERVAL '3 minutes'
                        WHERE id = ?
                          AND processing_version = ?
                          AND status = 'PROCESSING'
                        """,
                        documentId, claimedVersion
                );
                if (renewed == 0) {
                    log.warn("Worker v{} lost lease/ownership for document {} before batch embedding. Stopping immediately.",
                            claimedVersion, documentId);
                    return;
                }

                // 3b. Fetch next pending batch (P0 Requirement 2: ORDER BY chunk_index ASC LIMIT :batchSize)
                List<StagedChunk> batch = stagingRepository.findPendingChunks(documentId, maxBatchSize);
                if (batch.isEmpty()) {
                    log.info("All chunks embedded for document {} v{}. Ready for atomic publication.", documentId, claimedVersion);
                    break;
                }

                List<String> batchTexts = batch.stream().map(StagedChunk::content).toList();

                // 3c. Embed batch outside DB transaction (P0 Requirement 1, 6, 8, 10)
                List<float[]> batchEmbeddings = embeddingGateway.embedForIngestion(batchTexts);
                if (batchEmbeddings.size() != batch.size()) {
                    throw new IllegalStateException(String.format(
                            "Mismatch between batch chunk count (%d) and embedding count (%d)",
                            batch.size(), batchEmbeddings.size()));
                }

                // 3d. Fenced vector mutation (P0 Requirement 5)
                int updatedRows = stagingRepository.updateEmbeddingsFenced(documentId, claimedVersion, batch, batchEmbeddings);
                if (updatedRows == 0) {
                    log.warn("Worker v{} lost ownership for document {} during vector write (0 rows updated). Stopping immediately.",
                            claimedVersion, documentId);
                    return;
                }

                // 3e. Reset persisted consecutive failure counter on successful batch (P1 Requirement 14)
                jdbcTemplate.update("""
                        UPDATE documents
                        SET retry_count = 0
                        WHERE id = ?
                          AND processing_version = ?
                          AND status = 'PROCESSING'
                          AND retry_count > 0
                        """,
                        documentId, claimedVersion
                );
            }

            // 4. Fenced Atomic Publication (P0 Requirement 17)
            boolean finalized = finalizeFencedSuccess(documentId, claimedVersion, projectId);
            if (!finalized) {
                log.warn("Worker v{} for document {} was rejected during atomic publication (stale claim or incomplete).",
                        claimedVersion, documentId);
                return;
            }

            // 5. Post-commit staging cleanup outside publication transaction (P0 Requirement 18)
            try {
                stagingRepository.deleteStagedChunks(documentId);
                log.info("Post-publication staging cleanup completed for doc {}", documentId);
            } catch (Exception cleanupEx) {
                log.warn("Non-fatal error cleaning up staging chunks for doc {} v{}: {}",
                        documentId, claimedVersion, cleanupEx.getMessage());
            }

        } catch (QuotaBackpressureException qbe) {
            handleQuotaBackpressure(documentId, claimedVersion, qbe.getWaitMs());
        } catch (Exception e) {
            handleIngestionFailure(documentId, claimedVersion, e);
        }
    }

    @Override
    @Transactional
    public void deleteDocument(Long documentId) {
        if (documentId == null) {
            return;
        }

        documentRepository.findById(documentId).ifPresent(doc -> {
            log.info("Deleting document id={}, project={}, storageKey={}",
                    doc.getId(), doc.getProjectId(), doc.getStorageKey());

            // 1. Delete vector chunks from published index
            documentChunkRepository.deleteByDocumentId(documentId);

            // 2. Delete any lingering staging rows
            stagingRepository.deleteAllByDocumentId(documentId);

            // 3. Delete file in S3
            try {
                storageService.deleteFile("documents", doc.getStorageKey());
            } catch (Exception e) {
                log.warn("Failed to delete S3 file key={}: {}", doc.getStorageKey(), e.getMessage());
            }

            // 4. Delete document record
            documentRepository.delete(doc);
        });
    }

    /**
     * Atomically locks document row, verifies version + status, verifies 0 NULL embeddings,
     * copies staged chunks to published, marks READY, and clears lease. (P0 Requirement 17)
     */
    boolean finalizeFencedSuccess(Long documentId, int claimedVersion, Long projectId) {
        if (transactionTemplate == null) {
            return performFinalizationSql(documentId, claimedVersion, projectId);
        }
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> performFinalizationSql(documentId, claimedVersion, projectId)));
    }

    private boolean performFinalizationSql(Long documentId, int claimedVersion, Long projectId) {
        // 1. SELECT FOR UPDATE to lock document row & check processing_version + status
        List<Long> lockedIds = jdbcTemplate.query("""
                SELECT id
                FROM documents
                WHERE id = ?
                  AND processing_version = ?
                  AND status = 'PROCESSING'
                FOR UPDATE
                """,
                ps -> {
                    ps.setLong(1, documentId);
                    ps.setInt(2, claimedVersion);
                },
                (rs, rowNum) -> rs.getLong("id")
        );

        if (lockedIds.isEmpty()) {
            log.warn("Atomic publication lock failed: worker v{} lost ownership of doc {}", claimedVersion, documentId);
            return false;
        }

        // 2. Completeness check inside publication transaction
        Long nullCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunk_staging WHERE document_id = ? AND embedding IS NULL",
                Long.class,
                documentId
        );
        if (nullCount != null && nullCount > 0) {
            log.error("Atomic publication aborted: document {} has {} staging chunks with NULL embedding",
                    documentId, nullCount);
            return false;
        }

        // 3. Replace published chunks
        documentChunkRepository.deleteByDocumentId(documentId);
        int publishedCount = stagingRepository.copyStagedToPublished(documentId, projectId);

        // 4. Mark document READY
        int updated = jdbcTemplate.update("""
                UPDATE documents
                SET status = 'READY',
                    lease_until = NULL,
                    retry_count = 0,
                    error_message = NULL,
                    next_attempt_at = NULL,
                    updated_at = NOW()
                WHERE id = ?
                  AND processing_version = ?
                  AND status = 'PROCESSING'
                """,
                documentId, claimedVersion
        );

        log.info("Atomic publication successful: published {} chunks, document {} marked READY (version {})",
                publishedCount, documentId, claimedVersion);
        return updated > 0;
    }

    /**
     * Local quota backpressure handler: transitions PROCESSING -> RETRY_WAIT with next_attempt_at = NOW() + waitMs
     * and lease_until = NULL without incrementing retry_count. (P0 Requirement 9 & 13)
     */
    void handleQuotaBackpressure(Long documentId, int claimedVersion, long waitMs) {
        long waitSeconds = Math.max(1L, (long) Math.ceil(waitMs / 1000.0));
        log.info("Document {} v{} yielding to RETRY_WAIT due to local quota backpressure. Waiting {}s (waitMs={}). retry_count NOT incremented.",
                documentId, claimedVersion, waitSeconds, waitMs);

        int updated = jdbcTemplate.update("""
                UPDATE documents
                SET status = 'RETRY_WAIT',
                    lease_until = NULL,
                    next_attempt_at = NOW() + (? * INTERVAL '1 millisecond'),
                    error_message = ?,
                    updated_at = NOW()
                WHERE id = ?
                  AND processing_version = ?
                  AND status = 'PROCESSING'
                """,
                waitMs, "Quota backpressure: yielding for " + waitSeconds + "s", documentId, claimedVersion
        );
        if (updated == 0) {
            log.warn("Worker v{} lost ownership for document {} during quota backpressure transition", claimedVersion, documentId);
        }
    }

    /**
     * Fenced failure handling: distinguishes permanent exhaustion, 429 backoff, transient retries, and fatal errors.
     * Uses atomic conditional SQL update with processing_version and status = 'PROCESSING'. (P1 Requirement 14, 15, 16)
     */
    void handleIngestionFailure(Long documentId, int claimedVersion, Exception e) {
        List<Integer> retryCounts = jdbcTemplate.query(
                "SELECT retry_count FROM documents WHERE id = ? AND processing_version = ? AND status = 'PROCESSING'",
                ps -> {
                    ps.setLong(1, documentId);
                    ps.setInt(2, claimedVersion);
                },
                (rs, rowNum) -> rs.getInt("retry_count")
        );
        if (retryCounts.isEmpty()) {
            log.warn("Worker v{} lost ownership for document {} before failure handling. Aborting.", claimedVersion, documentId);
            return;
        }

        int currentRetryCount = retryCounts.get(0);
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (errorMsg.length() > 1000) {
            errorMsg = errorMsg.substring(0, 1000);
        }

        // 1. Detect explicit permanent/daily quota exhaustion (P1 Requirement 15)
        if (isDailyQuotaExhausted(e)) {
            log.error("Provider explicitly reported daily quota exhaustion for doc {} v{}: {}. Marking FAILED immediately.",
                    documentId, claimedVersion, errorMsg);
            markPermanentFailure(documentId, claimedVersion, "Daily quota exhausted: " + errorMsg);
            return;
        }

        // 2. Check if error is retryable (429, timeout, network failure, 5xx)
        if (!isRetryable(e)) {
            log.error("Ingestion failed with non-retryable error for doc {} v{}: {}. Marking FAILED.",
                    documentId, claimedVersion, errorMsg);
            markPermanentFailure(documentId, claimedVersion, errorMsg);
            return;
        }

        // 3. Retryable error: increment retryCount first (P1 Requirement 15 & 16)
        int newRetryCount = currentRetryCount + 1;
        int maxRetries = properties.getMaxRetryAttempts();
        if (newRetryCount >= maxRetries) {
            log.error("Doc {} v{} exceeded consecutive retry limit ({} >= {}). Marking FAILED.",
                    documentId, claimedVersion, newRetryCount, maxRetries);
            markPermanentFailure(documentId, claimedVersion, "Exceeded consecutive retry limit: " + errorMsg);
            return;
        }

        // 4. Calculate delay
        long delaySeconds;
        if (is429(e)) {
            long parsedRetryAfter = parseRetryAfterSeconds(e);
            long baseDelaySeconds = Math.max(parsedRetryAfter, 25L);
            long exponent = Math.max(0L, (long) newRetryCount - 1L);
            delaySeconds = Math.min(300L, baseDelaySeconds * (1L << exponent));
            log.warn("Provider 429 for doc {} v{}: retryCount={}, delaySeconds={}s (base={}s)",
                    documentId, claimedVersion, newRetryCount, delaySeconds, baseDelaySeconds);
        } else {
            long base = 5L;
            long exponent = Math.max(0L, (long) newRetryCount - 1L);
            long jitter = ThreadLocalRandom.current().nextLong(1, 4);
            delaySeconds = Math.min(300L, (base * (1L << exponent)) + jitter);
            log.warn("Transient error for doc {} v{}: retryCount={}, delaySeconds={}s",
                    documentId, claimedVersion, newRetryCount, delaySeconds);
        }

        // 5. Transition to RETRY_WAIT with lease_until = NULL (P0 Requirement 9 & P1 Requirement 15, 16)
        int updated = jdbcTemplate.update("""
                UPDATE documents
                SET status = 'RETRY_WAIT',
                    retry_count = ?,
                    lease_until = NULL,
                    next_attempt_at = NOW() + (? * INTERVAL '1 second'),
                    error_message = ?,
                    updated_at = NOW()
                WHERE id = ?
                  AND processing_version = ?
                  AND status = 'PROCESSING'
                """,
                newRetryCount, delaySeconds, errorMsg, documentId, claimedVersion
        );
        if (updated == 0) {
            log.warn("Worker v{} lost ownership for document {} during RETRY_WAIT transition", claimedVersion, documentId);
        }
    }

    private void markPermanentFailure(Long documentId, int claimedVersion, String errorMsg) {
        int updated = jdbcTemplate.update("""
                UPDATE documents
                SET status = 'FAILED',
                    lease_until = NULL,
                    next_attempt_at = NULL,
                    error_message = ?,
                    updated_at = NOW()
                WHERE id = ?
                  AND processing_version = ?
                  AND status = 'PROCESSING'
                """,
                errorMsg, documentId, claimedVersion
        );
        if (updated > 0) {
            try {
                // Section 18 / Invariant 7: Never delete staging chunks on failure.
                // Preserving staged chunks ensures already-computed embeddings are not lost
                // when recovering after quota restoration or key rotation.
                documentChunkRepository.deleteByDocumentId(documentId);
            } catch (Exception cleanupEx) {
                log.warn("Failed to clean up published chunks on permanent failure for doc {}: {}", documentId, cleanupEx.getMessage());
            }
        }
    }

    private static final java.util.regex.Pattern RETRY_IN_PATTERN =
            java.util.regex.Pattern.compile("retry in (\\d+(?:\\.\\d+)?)s", java.util.regex.Pattern.CASE_INSENSITIVE);

    public static long parseRetryAfterSeconds(Throwable t) {
        if (t == null) return 0L;
        String msg = t.getMessage();
        if (msg != null) {
            java.util.regex.Matcher matcher = RETRY_IN_PATTERN.matcher(msg);
            if (matcher.find()) {
                try {
                    double sec = Double.parseDouble(matcher.group(1));
                    return (long) Math.ceil(sec);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (t.getCause() != null && t.getCause() != t) {
            return parseRetryAfterSeconds(t.getCause());
        }
        return 0L;
    }

    public static boolean isDailyQuotaExhausted(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        if (msg.contains("requests_per_day") || msg.contains("tokens_per_day")
                || msg.contains("requestsperday") || msg.contains("tokensperday")
                || msg.contains("daily quota exhausted") || msg.contains("per_day")
                || msg.contains("perday")) {
            return true;
        }
        if (t.getCause() != null && t.getCause() != t) {
            return isDailyQuotaExhausted(t.getCause());
        }
        return false;
    }

    public static boolean is429(Throwable t) {
        if (t == null) return false;
        if (t instanceof QuotaExceededException) return true;
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        if (msg.contains("429") || msg.contains("resource_exhausted") || msg.contains("too many requests")) {
            return true;
        }
        if (t.getCause() != null && t.getCause() != t) {
            return is429(t.getCause());
        }
        return false;
    }

    public static boolean isRetryable(Throwable t) {
        if (t == null) return false;
        if (isDailyQuotaExhausted(t)) return false;
        if (is429(t)) return true;
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        if (msg.contains("quota") || msg.contains("rate limit") || msg.contains("timeout")
                || msg.contains("connection refused") || msg.contains("connection reset")
                || msg.contains("server error") || msg.contains("503") || msg.contains("500") || msg.contains("502")) {
            return true;
        }
        if (t instanceof java.io.IOException || t instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        if (t.getCause() != null && t.getCause() != t) {
            return isRetryable(t.getCause());
        }
        return false;
    }
}
