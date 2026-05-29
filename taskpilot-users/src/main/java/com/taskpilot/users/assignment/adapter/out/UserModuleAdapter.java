package com.taskpilot.users.assignment.adapter.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.dto.UserSkillDto;
import com.taskpilot.contracts.assignment.port.out.SystemSettingPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.assignment.port.out.UserSkillPort;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.NotificationPort;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.user.port.out.UserNotificationPort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.users.notifications.service.NotificationService;
import com.taskpilot.users.repository.SkillRepository;
import com.taskpilot.users.repository.SystemSettingRepository;
import com.taskpilot.users.repository.UserRepository;
import com.taskpilot.users.repository.UserSkillRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserModuleAdapter
        implements UserPort, UserSkillPort, SystemSettingPort, UserIdentityPort, NotificationPort, SkillPort,
        UserProfilePort, UserNotificationPort {

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final UserSkillRepository userSkillRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final SkillRepository skillRepository;

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
    public Optional<UserIdentityDto> findByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(user -> new UserIdentityDto(user.getId(), user.getEmail()));
    }

    @Override
    public Optional<UserProfileLiteDto> findLiteById(Long userId) {
        return userRepository.findById(userId)
                .map(user -> new UserProfileLiteDto(user.getId(), user.getFullName(), user.getAvatarUrl()));
    }

    @Override
    public List<UserProfileLiteDto> findLiteByIds(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userRepository.findAllById(userIds).stream()
                .map(user -> new UserProfileLiteDto(user.getId(), user.getFullName(), user.getAvatarUrl()))
                .collect(Collectors.toList());
    }

    @Override
    public List<UserProfileLiteDto> searchLite(String keyword, int limit) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return userRepository.findByKeyword(normalizedKeyword, PageRequest.of(0, safeLimit)).stream()
                .map(user -> new UserProfileLiteDto(user.getId(), user.getFullName(), user.getAvatarUrl()))
                .collect(Collectors.toList());
    }

    @Override
    public void createNotification(SystemNotificationCommandDto command) {
        notificationService.createNotification(command);
    }

    @Override
    public void sendSystemNotification(Long targetUserId, String title, String message, String linkAction) {
        notificationService.createSystemNotification(targetUserId, title, message, linkAction);
    }

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
}
