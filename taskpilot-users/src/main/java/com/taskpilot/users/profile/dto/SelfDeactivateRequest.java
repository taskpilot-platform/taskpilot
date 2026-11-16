package com.taskpilot.users.profile.dto;

import jakarta.validation.constraints.NotBlank;

public record SelfDeactivateRequest(
        @NotBlank(message = "Confirmation is required")
        String confirmation,
        String currentPassword,
        String reauthTicket
) {}
