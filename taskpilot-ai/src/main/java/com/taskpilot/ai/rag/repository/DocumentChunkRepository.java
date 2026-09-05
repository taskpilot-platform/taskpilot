package com.taskpilot.ai.rag.repository;

import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.ScoredChunk;

import java.util.List;

public interface DocumentChunkRepository {

    void saveAll(List<DocumentChunk> chunks);

    List<ScoredChunk> findNearestChunks(Long projectId, float[] queryVector, int limit, double minScore);

    void deleteByDocumentId(Long documentId);

    void deleteByProjectId(Long projectId);

    List<DocumentChunk> findByDocumentId(Long documentId);

    long countByProjectId(Long projectId);

    long countByDocumentId(Long documentId);
}

