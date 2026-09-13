package com.taskpilot.ai.rag.domain;

public record ScoredChunk(
        Long chunkId,
        Long documentId,
        Long projectId,
        int chunkIndex,
        String content,
        double similarity,
        String documentName
) {
    public ScoredChunk(Long chunkId, Long documentId, Long projectId, int chunkIndex, String content, double similarity) {
        this(chunkId, documentId, projectId, chunkIndex, content, similarity, null);
    }
}
