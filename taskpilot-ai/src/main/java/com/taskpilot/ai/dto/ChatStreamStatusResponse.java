package com.taskpilot.ai.dto;

import lombok.Builder;

import java.time.Instant;

@Builder
public record ChatStreamStatusResponse(
        Long sessionId,
        String clientMessageId,
        String phase,
        String modelUsed,
        Long assistantMessageId,
        String errorMessage,
        Instant updatedAt) {
}
