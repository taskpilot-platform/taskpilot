package com.taskpilot.ai.rag.repository;

import com.taskpilot.ai.rag.domain.StagedChunk;

import java.util.List;

/**
 * Repository interface for managing staged document chunks during RAG ingestion.
 * Enables resumable embedding, fencing isolation, and atomic publication.
 */
public interface DocumentChunkStagingRepository {

    /**
     * Persists initial plain-text chunks to the staging table before embedding begins.
     * All chunks are stored with embedding = NULL.
     */
    void stageInitialChunks(Long documentId, int processingVersion, List<String> contents);

    /**
     * Checks if staging rows already exist for this document and processing version.
     */
    boolean hasStagedChunks(Long documentId, int processingVersion);

    /**
     * Returns all chunks for this document and version that still require embeddings (embedding IS NULL),
     * ordered by chunk_index ASC.
     */
    List<StagedChunk> findPendingChunks(Long documentId, int processingVersion);

    /**
     * Returns all chunks for this document and version regardless of embedding state.
     */
    List<StagedChunk> findAllStagedChunks(Long documentId, int processingVersion);

    /**
     * Counts how many chunks still have embedding IS NULL for this document and version.
     */
    long countPendingChunks(Long documentId, int processingVersion);

    /**
     * Updates embeddings for the specified staged chunks.
     */
    void updateEmbeddings(List<StagedChunk> chunks, List<float[]> embeddings);

    /**
     * Atomically copies all staged chunks for (documentId, processingVersion) into the published
     * document_chunks table with the target projectId.
     *
     * @return number of rows inserted into document_chunks
     */
    int copyStagedToPublished(Long documentId, int processingVersion, Long projectId);

    /**
     * Cleans up staging rows for a specific document and version after publication.
     */
    void deleteStagedChunks(Long documentId, int processingVersion);

    /**
     * Adopts older staged chunks from previous versions to the current claimedVersion for this document.
     * Preserves all chunk texts and already-computed embeddings across job retries and restarts.
     *
     * @return number of staging rows updated to the current version
     */
    int adoptOlderStagedChunks(Long documentId, int currentVersion);

    /**
     * Cleans up older staging rows for a document (e.g. from previous failed or aborted versions).
     */
    void deleteOlderStagedChunks(Long documentId, int currentVersion);

    /**
     * Cleans up all staging rows for a document (e.g. when document is deleted).
     */
    void deleteAllByDocumentId(Long documentId);
}
