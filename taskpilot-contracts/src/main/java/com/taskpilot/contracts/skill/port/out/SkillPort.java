package com.taskpilot.contracts.skill.port.out;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.taskpilot.contracts.skill.dto.SkillDto;

public interface SkillPort {
    List<SkillDto> findByIds(Set<Long> ids);
    List<SkillDto> search(String keyword);        // for autocomplete
    Optional<SkillDto> findSkillById(Long id);
    boolean existsById(Long id);
}
