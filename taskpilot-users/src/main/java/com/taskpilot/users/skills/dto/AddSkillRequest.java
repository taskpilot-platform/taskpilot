package com.taskpilot.users.skills.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddSkillRequest(
        @NotBlank(message = "Skill name cannot be blank") 
        String name,

        @NotNull(message = "Level cannot be null") 
        @Min(value = 1, message = "Level must be between 1 and 5") 
        @Max(value = 5, message = "Level must be between 1 and 5") 
        Integer level
) {
}
