package com.taskpilot.projects.projects.dto;

import jakarta.validation.constraints.NotBlank;

public record AcceptProjectInvitationRequest(
        @NotBlank(message = "Invitation token is required")
        String token
) {}
