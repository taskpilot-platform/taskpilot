package com.taskpilot.projects.projects.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateProjectInvitationRequest(
        @NotBlank(message = "Invitee email is required")
        @Email(message = "Invalid email format")
        String email
) {}
