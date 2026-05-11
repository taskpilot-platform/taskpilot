package com.taskpilot.projects.tasks.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateLabelRequest(
    @NotBlank(message = "Label name is required")
    String name,
    String color
) {}
