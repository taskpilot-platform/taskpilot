package com.taskpilot.users.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminSkillRequest(
        @NotBlank(message = "Skill name cannot be blank")
        String name,

        String description
) {
}
