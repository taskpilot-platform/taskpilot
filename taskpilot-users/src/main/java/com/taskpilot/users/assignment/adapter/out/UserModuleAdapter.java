package com.taskpilot.users.assignment.adapter.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.dto.UserSkillDto;
import com.taskpilot.contracts.assignment.port.out.SystemSettingPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.assignment.port.out.UserSkillPort;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.users.repository.SystemSettingRepository;
import com.taskpilot.users.repository.UserRepository;
import com.taskpilot.users.repository.UserSkillRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserModuleAdapter
        implements UserPort, UserSkillPort, SystemSettingPort, UserIdentityPort {

    private final UserRepository userRepository;
    private final UserSkillRepository userSkillRepository;
    private final SystemSettingRepository systemSettingRepository;

    @Override
    public Optional<UserProfileDto> findById(Long userId) {
        return userRepository.findById(userId).map(user -> new UserProfileDto(user.getId(),
                user.getFullName(), user.getEmail(),
                user.getStatus().name(),
                user.getCurrentWorkload() != null ? user
                        .getCurrentWorkload()
                        : 0));
    }

    @Override
    public List<UserSkillDto> findByUserIdWithSkill(Long userId) {
        return userSkillRepository.findByIdUserIdWithSkill(userId).stream()
                .map(userSkill -> new UserSkillDto(userSkill.getSkill()
                        .getName(),
                        userSkill.getLevel() != null ? userSkill
                                .getLevel()
                                : 0))
                .toList();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Optional<Map<String, Object>> findJsonObjectByKey(String keyName) {
        return systemSettingRepository.findById(keyName)
                .filter(setting -> setting.getValueJson() instanceof Map<?, ?>)
                .map(setting -> (Map<String, Object>) setting
                        .getValueJson());
    }

    @Override
    public Optional<Long> findUserIdByEmail(String email) {
        return userRepository.findByEmail(email).map(user -> user.getId());
    }
}
