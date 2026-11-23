package com.taskpilot.users.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record CompletePasswordSetupRequest(
        @NotBlank(message = "Setup token is required")
        String token,
        @NotBlank(message = "New password is required")
        String newPassword
) {}
