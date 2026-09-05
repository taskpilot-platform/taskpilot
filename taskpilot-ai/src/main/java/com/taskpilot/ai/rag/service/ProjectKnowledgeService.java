package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.ScoredChunk;

import java.util.List;

public interface ProjectKnowledgeService {

    List<ScoredChunk> searchKnowledge(Long projectId, Long userId, String query, int limit, double minScore);

    String getKnowledgeContext(Long projectId, Long userId, String query, int maxChunks);
}
