package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.ScoredChunk;

import java.util.List;

public interface ProjectKnowledgeService {

    List<ScoredChunk> searchKnowledge(Long projectId, Long userId, String query, int limit, double minScore);

    List<ScoredChunk> searchKnowledge(Long projectId, Long documentId, Long userId, String query, int limit, double minScore);

    List<ScoredChunk> getContextChunks(Long projectId, Long userId, String query, int maxChunks);

    List<ScoredChunk> getContextChunks(Long projectId, Long documentId, Long userId, String query, int maxChunks);

    String getKnowledgeContext(Long projectId, Long userId, String query, int maxChunks);

    String getKnowledgeContext(Long projectId, Long documentId, Long userId, String query, int maxChunks);
}
