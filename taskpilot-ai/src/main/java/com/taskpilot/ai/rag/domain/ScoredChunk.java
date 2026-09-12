package com.taskpilot.ai.rag.domain;

public record ScoredChunk(
        Long chunkId,
        Long documentId,
        Long projectId,
        int chunkIndex,
        String content,
        double similarity
) {}
