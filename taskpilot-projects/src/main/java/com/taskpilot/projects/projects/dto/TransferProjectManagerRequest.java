package com.taskpilot.projects.projects.dto;

import jakarta.validation.constraints.NotNull;

public record TransferProjectManagerRequest(
        @NotNull(message = "Target user ID is required")
        Long targetUserId,
        String reason
) {}
