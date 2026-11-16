package com.taskpilot.users.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record BeginCredentialLinkRequest(
        @NotBlank(message = "Provider is required")
        String provider,
        String currentPassword,
        String reauthTicket
) {}
