package com.taskpilot.users.admin.dto;

import com.taskpilot.users.common.entity.SkillEntity;

public record AdminSkillResponse(
        Long id,
        String name,
        String description,
        Boolean isActive
) {
    public static AdminSkillResponse fromEntity(SkillEntity entity) {
        return new AdminSkillResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getIsActive()
        );
    }
}
