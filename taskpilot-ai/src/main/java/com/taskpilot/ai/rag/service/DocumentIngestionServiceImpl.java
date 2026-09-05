package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.infrastructure.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final StorageService storageService;
    private final DocumentTextExtractor documentTextExtractor;
    private final DocumentChunker documentChunker;
    private final EmbeddingService embeddingService;

    @Override
    @Transactional
    public void ingestDocument(Long documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }

        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        log.info("Starting ingestion for document id={}, project={}, file={}",
                document.getId(), document.getProjectId(), document.getOriginalFilename());

        document.setStatus(DocumentStatus.PROCESSING);
        document.setErrorMessage(null);
        documentRepository.save(document);

        try {
            // 1. Clean up any previous chunks for idempotence/re-ingestion
            documentChunkRepository.deleteByDocumentId(documentId);

            // 2. Download file from storage
            String text;
            try (InputStream stream = storageService.downloadFile(document.getStorageKey())) {
                text = documentTextExtractor.extractText(
                        stream,
                        document.getOriginalFilename(),
                        document.getContentType()
                );
            }

            if (text == null || text.isBlank()) {
                throw new IllegalStateException("Extracted text from document is empty");
            }

            // 3. Chunk text recursively
            List<String> textChunks = documentChunker.chunkText(text);
            if (textChunks.isEmpty()) {
                throw new IllegalStateException("Chunker produced 0 chunks from extracted text");
            }

            // 4. Generate embeddings via canonical embedding service
            List<float[]> embeddings = embeddingService.embedBatch(textChunks);
            if (embeddings.size() != textChunks.size()) {
                throw new IllegalStateException("Mismatch between chunk count (" + textChunks.size()
                        + ") and embedding count (" + embeddings.size() + ")");
            }

            // 5. Build and save DocumentChunks
            Instant now = Instant.now();
            List<DocumentChunk> chunks = new ArrayList<>(textChunks.size());
            for (int i = 0; i < textChunks.size(); i++) {
                chunks.add(new DocumentChunk(
                        null,
                        documentId,
                        document.getProjectId(),
                        i,
                        textChunks.get(i),
                        embeddings.get(i),
                        now
                ));
            }

            documentChunkRepository.saveAll(chunks);

            // 6. Transition status to READY
            document.setStatus(DocumentStatus.READY);
            document.setErrorMessage(null);
            documentRepository.save(document);

            log.info("Successfully ingested document id={}, created {} chunks", documentId, chunks.size());

        } catch (Exception e) {
            log.error("Failed to ingest document id={}, error={}", documentId, e.getMessage(), e);

            // Rollback partial chunks and mark FAILED
            try {
                documentChunkRepository.deleteByDocumentId(documentId);
            } catch (Exception cleanupEx) {
                log.warn("Failed to cleanup partial chunks for document id={}: {}", documentId, cleanupEx.getMessage());
            }

            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage(e.getMessage());
            documentRepository.save(document);

            throw new RuntimeException("Document ingestion failed: " + e.getMessage(), e);
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
}
