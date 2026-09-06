package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes document text extraction, deterministic chunking, and embedding generation
 * with optimistic fencing tokens (processing_version) and atomic SELECT FOR UPDATE finalization.
 * Network I/O (S3 and Google Gemini) is executed outside database transactions.
 */
@Slf4j
@Service
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
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
            StorageService storageService,
            DocumentTextExtractor documentTextExtractor,
            DocumentChunker documentChunker,
            EmbeddingGateway embeddingGateway,
            RagEmbeddingProperties properties,
            JdbcTemplate jdbcTemplate,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
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

        int currentRetryCount = document.getRetryCount();

        try {
            // 2. Non-transactional Network & CPU I/O - ZERO DB locks held
            String storageKey = document.getStorageKey();
            String originalFilename = document.getOriginalFilename();
            String contentType = document.getContentType();
            Long projectId = document.getProjectId();

            // 2a. Download from S3
            String text;
            try (InputStream stream = storageService.downloadFile("documents", storageKey)) {
                text = documentTextExtractor.extractText(stream, originalFilename, contentType);
            }

            if (text == null || text.isBlank()) {
                throw new IllegalStateException("Extracted text from document is empty");
            }

            // 2b. Recursive chunking
            List<String> textChunks = documentChunker.chunkText(text);
            if (textChunks.isEmpty()) {
                throw new IllegalStateException("Chunker produced 0 chunks from extracted text");
            }

            // 2c. Canonical Gemini embedding generation via EmbeddingGateway (shared RPM admission)
            List<float[]> embeddings = embeddingGateway.embedForIngestion(textChunks);
            if (embeddings.size() != textChunks.size()) {
                throw new IllegalStateException(String.format(
                        "Mismatch between chunk count (%d) and embedding count (%d)",
                        textChunks.size(), embeddings.size()));
            }

            // 2d. Build chunk domain models
            Instant now = Instant.now();
            List<DocumentChunk> chunks = new ArrayList<>(textChunks.size());
            for (int i = 0; i < textChunks.size(); i++) {
                chunks.add(new DocumentChunk(
                        null,
                        documentId,
                        projectId,
                        i,
                        textChunks.get(i),
                        embeddings.get(i),
                        now
                ));
            }

            // 3. Fenced Atomic Finalization: lock document row, verify version, persist chunks, mark READY
            boolean finalized = finalizeFencedSuccess(documentId, claimedVersion, chunks);
            if (!finalized) {
                log.warn("Worker v{} for document {} was rejected during finalization (stale claim).",
                        claimedVersion, documentId);
            }

        } catch (Exception e) {
            handleIngestionFailure(documentId, claimedVersion, currentRetryCount, e);
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

            // 1. Delete vector chunks
            documentChunkRepository.deleteByDocumentId(documentId);

            // 2. Delete file in S3
            try {
                storageService.deleteFile("documents", doc.getStorageKey());
            } catch (Exception e) {
                log.warn("Failed to delete S3 file key={}: {}", doc.getStorageKey(), e.getMessage());
            }

            // 3. Delete document record
            documentRepository.delete(doc);
        });
    }

    /**
     * Atomically locks document, verifies version hasn't changed, replaces chunks, and marks READY.
     * Prevents TOCTOU races and zombie worker overwrite.
     */
    boolean finalizeFencedSuccess(Long documentId, int claimedVersion, List<DocumentChunk> chunks) {
        if (transactionTemplate == null) {
            return performFinalizationSql(documentId, claimedVersion, chunks);
        }
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> performFinalizationSql(documentId, claimedVersion, chunks)));
    }

    private boolean performFinalizationSql(Long documentId, int claimedVersion, List<DocumentChunk> chunks) {
        // 1. SELECT FOR UPDATE to lock row and check active version
        List<Integer> versions = jdbcTemplate.query(
                "SELECT processing_version FROM documents WHERE id = ? AND status = 'PROCESSING' FOR UPDATE",
                ps -> ps.setLong(1, documentId),
                (rs, rowNum) -> rs.getInt("processing_version")
        );

        if (versions.isEmpty() || versions.get(0) != claimedVersion) {
            log.warn("Zombie finalize rejection: document {} has status/version {} but claimed version was {}.",
                    documentId, versions.isEmpty() ? "NOT_PROCESSING" : versions.get(0), claimedVersion);
            return false;
        }

        // 2. Replace chunks atomically while holding row lock
        documentChunkRepository.deleteByDocumentId(documentId);
        documentChunkRepository.saveAll(chunks);

        // 3. Mark document READY and reset retry/lease hygiene
        int updated = jdbcTemplate.update("""
                UPDATE documents
                SET status = 'READY',
                    retry_count = 0,
                    next_attempt_at = NULL,
                    lease_until = NULL,
                    error_message = NULL,
                    updated_at = NOW()
                WHERE id = ?
                  AND processing_version = ?
                  AND status = 'PROCESSING'
                """,
                documentId, claimedVersion
        );

        return updated > 0;
    }

    /**
     * Fenced failure handling: distinguishes retryable errors from non-retryable errors.
     * Uses atomic conditional SQL update with processing_version and status = 'PROCESSING'.
     */
    void handleIngestionFailure(Long documentId, int claimedVersion, int currentRetryCount, Exception e) {
        boolean retryable = isRetryable(e);
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (errorMsg.length() > 1000) {
            errorMsg = errorMsg.substring(0, 1000);
        }

        if (retryable) {
            long backoffSeconds = calculateBackoff(currentRetryCount, e);
            int maxRetries = properties.getMaxRetryAttempts();
            log.warn("Ingestion failed with retryable error for doc {} v{}: {}. Scheduling retry with backoff {}s (current retry count: {})",
                    documentId, claimedVersion, errorMsg, backoffSeconds, currentRetryCount);

            int updated = jdbcTemplate.update("""
                    UPDATE documents
                    SET status = CASE
                            WHEN retry_count >= ?
                                THEN 'FAILED'
                            ELSE 'RETRY_WAIT'
                        END,
                        retry_count = CASE
                            WHEN retry_count >= ?
                                THEN retry_count
                            ELSE retry_count + 1
                        END,
                        next_attempt_at = CASE
                            WHEN retry_count >= ?
                                THEN NULL
                            ELSE NOW() + (? * INTERVAL '1 second')
                        END,
                        error_message = ?,
                        lease_until = NULL,
                        updated_at = NOW()
                    WHERE id = ?
                      AND processing_version = ?
                      AND status = 'PROCESSING'
                    """,
                    maxRetries, maxRetries, maxRetries, backoffSeconds, errorMsg, documentId, claimedVersion
            );

            if (updated == 0) {
                log.warn("Zombie failure rejection: update affected 0 rows for doc {} v{}. Worker state discarded.",
                        documentId, claimedVersion);
            }
        } else {
            log.error("Ingestion failed with permanent non-retryable error for doc {} v{}: {}. Marking FAILED.",
                    documentId, claimedVersion, errorMsg);

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

            if (updated == 0) {
                log.warn("Zombie failure rejection: permanent failure update affected 0 rows for doc {} v{}.",
                        documentId, claimedVersion);
            } else {
                try {
                    documentChunkRepository.deleteByDocumentId(documentId);
                } catch (Exception cleanupEx) {
                    log.warn("Failed to clean up chunks on permanent failure for doc {}: {}", documentId, cleanupEx.getMessage());
                }
            }
        }
    }

    public static boolean isRetryable(Throwable t) {
        if (t == null) return false;
        if (t instanceof QuotaExceededException) return true;
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        if (msg.contains("429") || msg.contains("too many requests") || msg.contains("resource_exhausted")
                || msg.contains("quota") || msg.contains("rate limit") || msg.contains("timeout")
                || msg.contains("connection refused") || msg.contains("connection reset")) {
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

    public static long calculateBackoff(int retryCount, Exception e) {
        long base = 5;
        String msg = e != null && e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (e instanceof QuotaExceededException || msg.contains("429") || msg.contains("resource_exhausted")) {
            base = 10;
        }
        long exponential = base * (1L << Math.min(retryCount, 5));
        long jitter = ThreadLocalRandom.current().nextLong(1, 4);
        return Math.min(exponential + jitter, 300);
    }
}
