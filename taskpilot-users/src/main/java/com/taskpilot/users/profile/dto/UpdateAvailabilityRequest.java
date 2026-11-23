package com.taskpilot.users.profile.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAvailabilityRequest(
        @NotBlank(message = "Availability is required")
        String availability
) {}
