package com.taskpilot.contracts.assignment.port.out;

import java.util.List;

import com.taskpilot.contracts.assignment.dto.UserSkillDto;

public interface UserSkillPort {

    List<UserSkillDto> findByUserIdWithSkill(Long userId);
}
