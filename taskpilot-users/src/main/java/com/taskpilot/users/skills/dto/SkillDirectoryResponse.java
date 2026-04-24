package com.taskpilot.users.skills.dto;

import com.taskpilot.users.entity.SkillEntity;

public record SkillDirectoryResponse(
        Long id,
        String name,
        String description
) {
    public static SkillDirectoryResponse fromEntity(SkillEntity skill) {
        return new SkillDirectoryResponse(skill.getId(), skill.getName(), skill.getDescription());
    }
}
