package com.taskpilot.ai.rag.domain;

import java.time.Instant;

public record DocumentChunk(
        Long id,
        Long documentId,
        Long projectId,
        int chunkIndex,
        String content,
        float[] embedding,
        Instant createdAt
) {}
