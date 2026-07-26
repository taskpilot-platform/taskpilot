package com.taskpilot.users.notifications.adapter.out;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.contracts.user.dto.NotificationSummaryDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.port.out.NotificationPort;
import com.taskpilot.contracts.user.port.out.UserNotificationPort;
import com.taskpilot.contracts.user.port.out.UserNotificationQueryPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.common.repository.NotificationRepository;
import com.taskpilot.users.notifications.service.NotificationService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserNotificationAdapter implements NotificationPort, UserNotificationPort, UserNotificationQueryPort {

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

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
}
