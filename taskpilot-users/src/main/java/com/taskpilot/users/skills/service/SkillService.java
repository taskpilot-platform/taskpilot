package com.taskpilot.users.skills.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.entity.SkillEntity;
import com.taskpilot.users.entity.UserEntity;
import com.taskpilot.users.entity.UserSkillEntity;
import com.taskpilot.users.entity.UserSkillId;
import com.taskpilot.users.repository.SkillRepository;
import com.taskpilot.users.repository.UserRepository;
import com.taskpilot.users.repository.UserSkillRepository;
import com.taskpilot.users.skills.dto.AddSkillRequest;
import com.taskpilot.users.skills.dto.UpdateSkillRequest;
import com.taskpilot.users.skills.dto.UserSkillResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SkillService {

    private final UserRepository userRepository;
    private final SkillRepository skillRepository;
    private final UserSkillRepository userSkillRepository;

    private UserEntity getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found"));
    }

    public List<UserSkillResponse> getMySkills() {
        UserEntity user = getCurrentUser();
        return userSkillRepository.findByIdUserId(user.getId()).stream()
                .map(UserSkillResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public UserSkillResponse getSkillDetail(Long skillId) {
        UserEntity user = getCurrentUser();
        UserSkillId id = new UserSkillId(user.getId(), skillId);
        UserSkillEntity us = userSkillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Skill not found for user"));
        return UserSkillResponse.fromEntity(us);
    }

    @Transactional
    public void addSkill(AddSkillRequest request) {
        UserEntity user = getCurrentUser();

        SkillEntity skill = skillRepository.findByName(request.name())
                .orElseGet(() -> {
                    SkillEntity newSkill = SkillEntity.builder().name(request.name()).build();
                    return skillRepository.save(newSkill);
                });

        UserSkillId id = new UserSkillId(user.getId(), skill.getId());
        if (userSkillRepository.existsById(id)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "User already has this skill");
        }

        UserSkillEntity userSkill = UserSkillEntity.builder()
                .id(id)
                .user(user)
                .skill(skill)
                .level(request.level())
                .build();

        userSkillRepository.save(userSkill);
    }

    @Transactional
    public void updateSkill(Long skillId, UpdateSkillRequest request) {
        UserEntity user = getCurrentUser();
        UserSkillId id = new UserSkillId(user.getId(), skillId);
        UserSkillEntity us = userSkillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Skill not found for user"));

        us.setLevel(request.level());
        userSkillRepository.save(us);
    }

    @Transactional
    public void deleteSkill(Long skillId) {
        UserEntity user = getCurrentUser();
        UserSkillId id = new UserSkillId(user.getId(), skillId);
        if (!userSkillRepository.existsById(id)) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Skill not found for user");
        }
        userSkillRepository.deleteById(id);
    }
}
