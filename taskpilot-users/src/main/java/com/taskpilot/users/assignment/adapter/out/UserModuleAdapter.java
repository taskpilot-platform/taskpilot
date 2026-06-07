package com.taskpilot.users.assignment.adapter.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.dto.UserSkillDto;
import com.taskpilot.contracts.assignment.port.out.SystemSettingPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.assignment.port.out.UserSkillPort;
import com.taskpilot.contracts.user.dto.NotificationSummaryDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.NotificationPort;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.user.port.out.UserNotificationPort;
import com.taskpilot.contracts.user.port.out.UserNotificationQueryPort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.skill.dto.UserSkillSummaryDto;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.users.entity.SkillEntity;
import com.taskpilot.users.entity.UserEntity;
import com.taskpilot.users.entity.UserSkillEntity;
import com.taskpilot.users.entity.UserSkillId;
import com.taskpilot.users.notifications.service.NotificationService;
import com.taskpilot.users.repository.NotificationRepository;
import com.taskpilot.users.repository.SkillRepository;
import com.taskpilot.users.repository.SystemSettingRepository;
import com.taskpilot.users.repository.UserRepository;
import com.taskpilot.users.repository.UserSkillRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserModuleAdapter
        implements UserPort, UserSkillPort, SystemSettingPort, UserIdentityPort, NotificationPort, SkillPort,
        UserProfilePort, UserNotificationPort, UserNotificationQueryPort {

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
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
    public List<NotificationSummaryDto> getMyNotifications(Long userId, boolean unreadOnly, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, safeLimit))
                .getContent()
                .stream()
                .filter(notification -> !unreadOnly || !Boolean.TRUE.equals(notification.getIsRead()))
                .map(notification -> new NotificationSummaryDto(
                        notification.getId(),
                        notification.getTitle(),
                        notification.getMessage(),
                        notification.getType() != null ? notification.getType().name() : null,
                        notification.getIsRead(),
                        notification.getLinkAction(),
                        notification.getCreatedAt()))
                .toList();
    }

    @Override
    public long getUnreadNotificationCount(Long userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    @Override
    @Transactional
    public NotificationSummaryDto markNotificationRead(Long notificationId, Long userId) {
        return notificationRepository.findByIdAndUserId(notificationId, userId)
                .map(notification -> {
                    notification.setIsRead(true);
                    var saved = notificationRepository.save(notification);
                    return new NotificationSummaryDto(
                            saved.getId(),
                            saved.getTitle(),
                            saved.getMessage(),
                            saved.getType() != null ? saved.getType().name() : null,
                            saved.getIsRead(),
                            saved.getLinkAction(),
                            saved.getCreatedAt());
                })
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Notification not found"));
    }

    @Override
    @Transactional
    public int markAllNotificationsRead(Long userId) {
        return notificationRepository.markAllAsReadByUserId(userId);
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
        if (user.getRole() != UserEntity.UserRole.ADMIN) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Admin permission is required");
        }
    }
}
