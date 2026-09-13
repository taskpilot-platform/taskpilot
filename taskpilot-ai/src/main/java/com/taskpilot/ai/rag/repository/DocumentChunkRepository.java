package com.taskpilot.ai.rag.repository;

import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.ScoredChunk;

import java.util.List;

public interface DocumentChunkRepository {

    void saveAll(List<DocumentChunk> chunks);

    List<ScoredChunk> findByProjectAndNearest(Long projectId, float[] queryVector, int candidateLimit, double minScore);

    default List<ScoredChunk> findByDocumentAndNearest(Long projectId, Long documentId, float[] queryVector, int candidateLimit, double minScore) {
        return findByProjectAndNearest(projectId, queryVector, candidateLimit, minScore).stream()
                .filter(chunk -> documentId != null && documentId.equals(chunk.documentId()))
                .limit(candidateLimit)
                .toList();
    }

    default List<ScoredChunk> findNearestChunks(Long projectId, float[] queryVector, int limit, double minScore) {
        return findByProjectAndNearest(projectId, queryVector, limit, minScore);
    }

    void deleteByDocumentId(Long documentId);

    void deleteByProjectId(Long projectId);

    List<DocumentChunk> findByDocumentId(Long documentId);

    long countByProjectId(Long projectId);

    long countByDocumentId(Long documentId);
}

