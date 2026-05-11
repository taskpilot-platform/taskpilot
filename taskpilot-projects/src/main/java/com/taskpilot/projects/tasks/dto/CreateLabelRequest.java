package com.taskpilot.projects.tasks.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateLabelRequest(
    @NotBlank(message = "Label name is required")
    String name,
    
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must be a valid hex code (e.g., #RRGGBB)")
    String color
) {}
