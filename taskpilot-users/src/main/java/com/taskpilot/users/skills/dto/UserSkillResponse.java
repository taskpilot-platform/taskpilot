package com.taskpilot.users.skills.dto;

import com.taskpilot.users.entity.UserSkillEntity;

public record UserSkillResponse(
        Long skillId,
        String name,
        Integer level
) {
    public static UserSkillResponse fromEntity(UserSkillEntity userSkill) {
        return new UserSkillResponse(
                userSkill.getSkill().getId(),
                userSkill.getSkill().getName(),
                userSkill.getLevel()
        );
    }
}
