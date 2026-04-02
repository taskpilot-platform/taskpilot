package com.taskpilot.projects.projects.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinProjectRequest(
        @NotBlank(message = "Project code cannot be blank")
        String projectCode
) {
}
