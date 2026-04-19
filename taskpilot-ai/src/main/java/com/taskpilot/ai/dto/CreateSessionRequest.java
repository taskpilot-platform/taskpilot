package com.taskpilot.ai.dto;
import jakarta.validation.constraints.Size;
public record CreateSessionRequest(
    @Size(max = 100, message = "Title must not exceed 100 characters")
    String title
) {}
