package com.taskpilot.users.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(
        @NotBlank(message = "Verification token is required")
        String token,
        @NotBlank(message = "Password is required")
        String newPassword
) {}
