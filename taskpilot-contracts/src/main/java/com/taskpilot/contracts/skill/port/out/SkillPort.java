package com.taskpilot.contracts.skill.port.out;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.skill.dto.UserSkillSummaryDto;

public interface SkillPort {
    List<SkillDto> findByIds(Set<Long> ids);
    List<SkillDto> search(String keyword);        // for autocomplete
    Optional<SkillDto> findSkillById(Long id);
    boolean existsById(Long id);
    SkillDto createSystemSkill(String name, String description, Long requesterUserId);
    SkillDto patchSystemSkill(Long skillId, String name, String description, Long requesterUserId);
    void deleteSystemSkill(Long skillId, Long requesterUserId);
    List<UserSkillSummaryDto> getMySkills(Long requesterUserId);
    UserSkillSummaryDto addMySkill(Long skillId, Integer level, Long requesterUserId);
    UserSkillSummaryDto updateMySkill(Long skillId, Integer level, Long requesterUserId);
    void deleteMySkill(Long skillId, Long requesterUserId);
}
