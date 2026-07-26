package com.taskpilot.users.skills.adapter.out;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.skill.dto.UserSkillSummaryDto;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.common.entity.SkillEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.entity.UserSkillEntity;
import com.taskpilot.users.common.entity.UserSkillId;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.repository.SkillRepository;
import com.taskpilot.users.common.repository.UserRepository;
import com.taskpilot.users.common.repository.UserSkillRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserSkillModuleAdapter implements SkillPort {

    private final UserRepository userRepository;
    private final UserSkillRepository userSkillRepository;
    private final SkillRepository skillRepository;

    @Override
    public List<SkillDto> findByIds(Set<Long> ids) {
        return skillRepository.findAllById(ids).stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsActive()))
                .map(s -> new SkillDto(s.getId(), s.getName(), s.getDescription()))
                .toList();
    }

    @Override
    public List<SkillDto> search(String keyword) {
        return skillRepository
                .findByNameContainingIgnoreCaseAndIsActiveTrue(keyword, PageRequest.of(0, 20))
                .stream()
                .map(s -> new SkillDto(s.getId(), s.getName(), s.getDescription()))
                .toList();
    }

    @Override
    public Optional<SkillDto> findSkillById(Long id) {
        return skillRepository.findByIdAndIsActiveTrue(id)
                .map(s -> new SkillDto(s.getId(), s.getName(), s.getDescription()));
    }

    @Override
    public boolean existsById(Long id) {
        return skillRepository.existsByIdAndIsActiveTrue(id);
    }

    @Override
    @Transactional
    public SkillDto createSystemSkill(String name, String description, Long requesterUserId) {
        requireAdmin(requesterUserId);
        String normalizedName = normalizeSkillName(name);
        if (skillRepository.existsByNameIgnoreCase(normalizedName)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Skill with name '" + normalizedName + "' already exists");
        }
        SkillEntity skill = SkillEntity.builder()
                .name(normalizedName)
                .description(description)
                .build();
        return toSkillDto(skillRepository.save(skill));
    }

    @Override
    @Transactional
    public SkillDto patchSystemSkill(Long skillId, String name, String description, Long requesterUserId) {
        requireAdmin(requesterUserId);
        SkillEntity skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Skill not found"));
        if (name != null && !name.isBlank()) {
            String normalizedName = normalizeSkillName(name);
            skillRepository.findByName(normalizedName)
                    .ifPresent(existing -> {
                        if (!existing.getId().equals(skillId)) {
                            throw new BusinessException(HttpStatus.CONFLICT.value(),
                                    "Skill with name '" + normalizedName + "' already exists");
                        }
                    });
            skill.setName(normalizedName);
        }
        if (description != null) {
            skill.setDescription(description);
        }
        return toSkillDto(skillRepository.save(skill));
    }

    @Override
    @Transactional
    public void deleteSystemSkill(Long skillId, Long requesterUserId) {
        requireAdmin(requesterUserId);
        SkillEntity skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Skill not found"));
        skill.setIsActive(false);
        skillRepository.save(skill);
    }

    @Override
    public List<UserSkillSummaryDto> getMySkills(Long requesterUserId) {
        return userSkillRepository.findByIdUserIdWithSkill(requesterUserId).stream()
                .map(this::toUserSkillSummary)
                .toList();
    }

    @Override
    @Transactional
    public UserSkillSummaryDto addMySkill(Long skillId, Integer level, Long requesterUserId) {
        UserEntity user = userRepository.findById(requesterUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));
        SkillEntity skill = skillRepository.findByIdAndIsActiveTrue(skillId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Skill does not exist in system directory"));
        UserSkillId id = new UserSkillId(requesterUserId, skillId);
        if (userSkillRepository.existsById(id)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "User already has this skill");
        }
        UserSkillEntity userSkill = UserSkillEntity.builder()
                .id(id)
                .user(user)
                .skill(skill)
                .level(level)
                .build();
        return toUserSkillSummary(userSkillRepository.save(userSkill));
    }

    @Override
    @Transactional
    public UserSkillSummaryDto updateMySkill(Long skillId, Integer level, Long requesterUserId) {
        UserSkillId id = new UserSkillId(requesterUserId, skillId);
        UserSkillEntity userSkill = userSkillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Skill not found for user"));
        userSkill.setLevel(level);
        return toUserSkillSummary(userSkillRepository.save(userSkill));
    }

    @Override
    @Transactional
    public void deleteMySkill(Long skillId, Long requesterUserId) {
        UserSkillId id = new UserSkillId(requesterUserId, skillId);
        if (!userSkillRepository.existsById(id)) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Skill not found for user");
        }
        userSkillRepository.deleteById(id);
    }

    private UserSkillSummaryDto toUserSkillSummary(UserSkillEntity userSkill) {
        SkillEntity skill = userSkill.getSkill();
        return new UserSkillSummaryDto(skill.getId(), skill.getName(), userSkill.getLevel());
    }

    private SkillDto toSkillDto(SkillEntity skill) {
        return new SkillDto(skill.getId(), skill.getName(), skill.getDescription());
    }

    private String normalizeSkillName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Skill name cannot be blank");
        }
        return name.trim().replaceAll("\\s+", " ");
    }

    private void requireAdmin(Long requesterUserId) {
        UserEntity user = userRepository.findById(requesterUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));
        if (user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Admin permission is required");
        }
    }
}
