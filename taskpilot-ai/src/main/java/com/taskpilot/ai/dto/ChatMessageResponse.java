package com.taskpilot.ai.dto;
import lombok.Builder;
import java.time.Instant;
@Builder
public record ChatMessageResponse(
    Long id,
    Long sessionId,
    String sender,
    String content,
    Instant createdAt
) {}
