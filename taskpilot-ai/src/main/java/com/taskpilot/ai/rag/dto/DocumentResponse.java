package com.taskpilot.ai.rag.dto;

import com.taskpilot.ai.rag.domain.DocumentStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record DocumentResponse(
        Long id,
        Long projectId,
        String originalFilename,
        String contentType,
        Long fileSize,
        DocumentStatus status,
        String errorMessage,
        Long chunkCount,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
