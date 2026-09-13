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
    void stageInitialChunks(Long documentId, List<String> contents);

    /**
     * Checks if staging rows already exist for this document.
     */
    boolean hasStagedChunks(Long documentId);

    /**
     * Returns pending chunks for this document (embedding IS NULL), ordered by chunk_index ASC.
     */
    List<StagedChunk> findPendingChunks(Long documentId, int limit);

    /**
     * Returns all chunks for this document regardless of embedding state.
     */
    List<StagedChunk> findAllStagedChunks(Long documentId);

    /**
     * Counts how many chunks still have embedding IS NULL for this document.
     */
    long countPendingChunks(Long documentId);

    /**
     * Updates embeddings for the specified staged chunks with processing_version fencing.
     * Checks ownership against documents table.
     *
     * @return number of staging rows updated (0 if worker lost ownership)
     */
    int updateEmbeddingsFenced(Long documentId, int workerVersion, List<StagedChunk> chunks, List<float[]> embeddings);

    /**
     * Atomically copies all staged chunks for documentId into the published
     * document_chunks table with the target projectId.
     *
     * @return number of rows inserted into document_chunks
     */
    int copyStagedToPublished(Long documentId, Long projectId);

    /**
     * Cleans up all staging rows for a specific document.
     */
    void deleteStagedChunks(Long documentId);

    /**
     * Cleans up all staging rows for a document (alias for deleteStagedChunks).
     */
    void deleteAllByDocumentId(Long documentId);
}
