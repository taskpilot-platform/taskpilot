package com.taskpilot.ai.dto;
import lombok.Builder;
import java.time.Instant;
@Builder
public record AiLogResponse(
    Long id,
    Long userId,
    Long projectId,
    Long sessionId,
    String request,
    String response,
    String reasoning,
    String actionTaken,
    String modelUsed,
    Integer tokensUsed,
    Integer durationMs,
    String humanFeedback,
    Instant createdAt
) {}
