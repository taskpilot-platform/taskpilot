package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.infrastructure.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@Service
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private static final Duration DEFAULT_STUCK_TIMEOUT = Duration.ofMinutes(15);

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final StorageService storageService;
    private final DocumentTextExtractor documentTextExtractor;
    private final DocumentChunker documentChunker;
    private final EmbeddingService embeddingService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public DocumentIngestionServiceImpl(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            StorageService storageService,
            DocumentTextExtractor documentTextExtractor,
            DocumentChunker documentChunker,
            EmbeddingService embeddingService,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.storageService = storageService;
        this.documentTextExtractor = documentTextExtractor;
        this.documentChunker = documentChunker;
        this.embeddingService = embeddingService;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    public DocumentIngestionServiceImpl(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            StorageService storageService,
            DocumentTextExtractor documentTextExtractor,
            DocumentChunker documentChunker,
            EmbeddingService embeddingService) {
        this(documentRepository, documentChunkRepository, storageService, documentTextExtractor, documentChunker, embeddingService, null);
    }

    /**
     * Executes the ingestion pipeline without holding a database transaction during S3 download
     * or Google Gemini embedding API network calls.
     */
    @Override
    public void ingestDocument(Long documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }

        // STEP 1: Short DB Transaction - Mark document PROCESSING
        DocumentEntity document = callInTransaction(status -> {
            DocumentEntity doc = documentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
            log.info("Starting ingestion for document id={}, project={}, file={}",
                    doc.getId(), doc.getProjectId(), doc.getOriginalFilename());
            doc.setStatus(DocumentStatus.PROCESSING);
            doc.setErrorMessage(null);
            documentRepository.save(doc);
            return doc;
        });


        try {
            // STEP 2: Non-Transactional Execution - ZERO DB connections held during network & CPU I/O
            String storageKey = document.getStorageKey();
            String originalFilename = document.getOriginalFilename();
            String contentType = document.getContentType();
            Long projectId = document.getProjectId();

            // 2a. Download from S3 storage
            String text;
            try (InputStream stream = storageService.downloadFile(storageKey)) {
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

            // 2c. Canonical Gemini embedding generation (Network I/O)
            List<float[]> embeddings = embeddingService.embedBatch(textChunks);
            if (embeddings.size() != textChunks.size()) {
                throw new IllegalStateException("Mismatch between chunk count (" + textChunks.size()
                        + ") and embedding count (" + embeddings.size() + ")");
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

            // STEP 3: Short DB Transaction - Batch persist vector chunks and mark READY
            runInTransaction(status -> {
                documentChunkRepository.deleteByDocumentId(documentId);
                documentChunkRepository.saveAll(chunks);

                DocumentEntity entity = documentRepository.findById(documentId)
                        .orElseThrow(() -> new IllegalStateException("Document missing on completion: " + documentId));
                entity.setStatus(DocumentStatus.READY);
                entity.setErrorMessage(null);
                documentRepository.save(entity);

                log.info("Successfully ingested document id={}, created {} chunks", documentId, chunks.size());
            });

        } catch (Exception e) {
            log.error("Failed to ingest document id={}, error={}", documentId, e.getMessage(), e);

            // STEP 4: Short DB Transaction - Cleanup partial chunks and mark FAILED
            runInTransaction(status -> {
                try {
                    documentChunkRepository.deleteByDocumentId(documentId);
                } catch (Exception cleanupEx) {
                    log.warn("Failed to cleanup partial chunks for document id={}: {}", documentId, cleanupEx.getMessage());
                }

                documentRepository.findById(documentId).ifPresent(doc -> {
                    doc.setStatus(DocumentStatus.FAILED);
                    doc.setErrorMessage(e.getMessage());
                    documentRepository.save(doc);
                });
            });

            throw new RuntimeException("Document ingestion failed: " + e.getMessage(), e);
        }
    }

    @Async
    @Override
    public void ingestDocumentAsync(Long documentId) {
        try {
            ingestDocument(documentId);
        } catch (Exception e) {
            log.error("Async document ingestion failed for document id={}: {}", documentId, e.getMessage());
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
                storageService.deleteFile(doc.getStorageKey());
            } catch (Exception e) {
                log.warn("Failed to delete S3 file key={}: {}", doc.getStorageKey(), e.getMessage());
            }

            // 3. Delete document record
            documentRepository.delete(doc);
        });
    }

    /**
     * Crash recovery: Scans for documents stuck in PROCESSING longer than the specified threshold.
     * Transitions them to FAILED (does NOT auto-reingest to prevent duplicate workloads) so users can Retry.
     */
    @Override
    @Transactional
    public int recoverStuckDocuments(Duration staleThreshold) {
        Instant threshold = Instant.now().minus(staleThreshold);
        List<DocumentEntity> stuckDocuments = documentRepository.findByStatusAndUpdatedAtBefore(
                DocumentStatus.PROCESSING, threshold
        );

        if (stuckDocuments.isEmpty()) {
            return 0;
        }

        log.warn("Found {} stale documents stuck in PROCESSING before {}. Recovering to FAILED...",
                stuckDocuments.size(), threshold);

        for (DocumentEntity doc : stuckDocuments) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage("Processing timed out or was interrupted by system restart. Please retry.");
            documentChunkRepository.deleteByDocumentId(doc.getId());
            documentRepository.save(doc);
            log.info("Recovered stuck document id={}, project={} to FAILED", doc.getId(), doc.getProjectId());
        }

        return stuckDocuments.size();
    }

    /**
     * Periodic scheduled recovery for documents stuck in PROCESSING (runs every 15 minutes).
     */
    @Scheduled(fixedDelay = 900000, initialDelay = 60000)
    public void scheduledStuckDocumentRecovery() {
        try {
            int recovered = recoverStuckDocuments(DEFAULT_STUCK_TIMEOUT);
            if (recovered > 0) {
                log.info("Scheduled recovery cleaned up {} stuck documents", recovered);
            }
        } catch (Exception e) {
            log.error("Scheduled stuck document recovery failed: {}", e.getMessage(), e);
        }
    }

    private void runInTransaction(Consumer<TransactionStatus> action) {
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(action);
        } else {
            action.accept(null);
        }
    }

    private <T> T callInTransaction(Function<TransactionStatus, T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(action::apply);
        } else {
            return action.apply(null);
        }
    }
}
