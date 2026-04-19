package com.taskpilot.ai.dto;
import lombok.Builder;
import java.time.Instant;
@Builder
public record ChatSessionResponse(
    Long id,
    String title,
    Instant createdAt,
    Instant updatedAt,
    long messageCount
) {}
