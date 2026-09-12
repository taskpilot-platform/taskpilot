package com.taskpilot.ai.rag.domain;

import java.time.Instant;

/**
 * Domain model representing a chunk in the staging area (document_chunk_staging).
 * Staged chunks represent work-in-progress during ingestion and allow resumable embedding
 * without mutating the active queryable index (document_chunks).
 */
public record StagedChunk(
        Long id,
        Long documentId,
        int processingVersion,
        int chunkIndex,
        String content,
        float[] embedding,
        Instant createdAt
) {
    public boolean hasEmbedding() {
        return embedding != null && embedding.length > 0;
    }
}
