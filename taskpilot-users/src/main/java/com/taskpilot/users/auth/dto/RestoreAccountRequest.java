package com.taskpilot.users.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RestoreAccountRequest(
        @NotBlank(message = "Restore ticket is required")
        String ticket
) {}
