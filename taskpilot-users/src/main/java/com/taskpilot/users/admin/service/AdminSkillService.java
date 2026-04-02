package com.taskpilot.users.admin.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.admin.dto.AdminSkillRequest;
import com.taskpilot.users.admin.dto.AdminSkillResponse;
import com.taskpilot.users.entity.SkillEntity;
import com.taskpilot.users.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSkillService {

    private final SkillRepository skillRepository;

    public Page<AdminSkillResponse> getAllSkills(String keyword, Pageable pageable) {
        Pageable safePageable = buildSafePageable(pageable, "id", "name", "description", "isActive");

        Page<SkillEntity> skills;
        if (keyword != null && !keyword.isBlank()) {
            skills = skillRepository.findByNameContainingIgnoreCase(keyword, safePageable);
        } else {
            skills = skillRepository.findAll(safePageable);
        }
        return skills.map(AdminSkillResponse::fromEntity);
    }

    public AdminSkillResponse getSkillDetail(Long id) {
        SkillEntity skill = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Skill not found"));
        return AdminSkillResponse.fromEntity(skill);
    }

    private Pageable buildSafePageable(Pageable pageable, String... allowedFields) {
        if (!pageable.getSort().isSorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.ASC, "id"));
        }

        java.util.Set<String> allowed = java.util.Set.of(allowedFields);
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.ASC, "id"));
            }
        }
        return pageable;
    }

    @Transactional
    public AdminSkillResponse createSkill(AdminSkillRequest request) {
        if (skillRepository.existsByNameIgnoreCase(request.name())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Skill with name '" + request.name() + "' already exists");
        }

        SkillEntity skill = SkillEntity.builder()
                .name(request.name())
                .description(request.description())
                .build();

        skillRepository.save(skill);
        return AdminSkillResponse.fromEntity(skill);
    }

    @Transactional
    public AdminSkillResponse updateSkill(Long id, AdminSkillRequest request) {
        SkillEntity skill = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Skill not found"));

        // Check duplicate name (exclude current skill)
        skillRepository.findByName(request.name())
                .ifPresent(existing -> {
                    if (!existing.getId().equals(id)) {
                        throw new BusinessException(HttpStatus.CONFLICT.value(),
                                "Skill with name '" + request.name() + "' already exists");
                    }
                });

        skill.setName(request.name());
        if (request.description() != null) {
            skill.setDescription(request.description());
        }

        skillRepository.save(skill);
        return AdminSkillResponse.fromEntity(skill);
    }

    @Transactional
    public void deleteSkill(Long id) {
        SkillEntity skill = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Skill not found"));

        skill.setIsActive(false);
        skillRepository.save(skill);
    }
}
