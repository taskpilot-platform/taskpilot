package com.taskpilot.users.notifications.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.notification.OneSignalService;
import com.taskpilot.users.entity.NotificationEntity;
import com.taskpilot.users.notifications.dto.NotificationResponse;
import com.taskpilot.users.repository.NotificationRepository;
import com.taskpilot.users.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final OneSignalService oneSignalService;

    public Page<NotificationResponse> getMyNotifications(String email, Pageable pageable) {
        Long userId = getCurrentUserIdByEmail(email);
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(NotificationResponse::fromEntity);
    }

    @Transactional
    public NotificationResponse markAsRead(Long notificationId, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        NotificationEntity notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Notification not found"));

        notification.setIsRead(true);
        return NotificationResponse.fromEntity(notificationRepository.save(notification));
    }

    @Transactional
    public int markAllAsRead(String email) {
        Long userId = getCurrentUserIdByEmail(email);
        return notificationRepository.markAllAsReadByUserId(userId);
    }

    public long getUnreadCount(String email) {
        Long userId = getCurrentUserIdByEmail(email);
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    @Transactional
    public void createSystemNotification(Long targetUserId, String title, String message, String linkAction) {
        NotificationEntity notification = NotificationEntity.builder()
                .userId(targetUserId)
                .title(title)
                .message(message)
                .type(NotificationEntity.NotificationType.SYSTEM)
                .isRead(false)
                .linkAction(linkAction)
                .build();

        notificationRepository.save(notification);
        oneSignalService.sendNotificationToUser(String.valueOf(targetUserId), title, message);
    }

    private Long getCurrentUserIdByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(user -> user.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }
}
