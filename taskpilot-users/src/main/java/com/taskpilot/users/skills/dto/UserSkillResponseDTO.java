package com.taskpilot.users.skills.dto;

import com.taskpilot.users.entity.UserSkillEntity;

public record UserSkillResponseDTO(
        Long skillId,
        String name,
        Integer level
) {
    public static UserSkillResponseDTO fromEntity(UserSkillEntity userSkill) {
        return new UserSkillResponseDTO(
                userSkill.getSkill().getId(),
                userSkill.getSkill().getName(),
                userSkill.getLevel()
        );
    }
}
