package com.taskpilot.users.notifications.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.taskpilot.contracts.user.dto.NotificationTypeDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
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
    private final NotificationRealtimeService notificationRealtimeService;

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

    public SseEmitter streamMyNotifications(String email) {
        Long userId = getCurrentUserIdByEmail(email);
        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
        return notificationRealtimeService.subscribe(userId, unreadCount);
    }

    @Transactional
    public void createSystemNotification(Long targetUserId, String title, String message, String linkAction) {
        createNotification(new SystemNotificationCommandDto(targetUserId, title, message, linkAction));
    }

    @Transactional
    public NotificationResponse createNotification(SystemNotificationCommandDto command) {
        NotificationEntity notification = NotificationEntity.builder()
                .userId(command.targetUserId())
                .title(command.title())
                .message(command.message())
                .type(toEntityType(command.type()))
                .isRead(false)
                .linkAction(command.linkAction())
                .build();

        NotificationResponse response = NotificationResponse.fromEntity(notificationRepository.save(notification));
        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(command.targetUserId());
        notificationRealtimeService.publishCreated(response, unreadCount);
        oneSignalService.sendNotificationToUser(String.valueOf(command.targetUserId()), command.title(), command.message());
        return response;
    }

    private Long getCurrentUserIdByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(user -> user.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }

    private NotificationEntity.NotificationType toEntityType(NotificationTypeDto type) {
        return NotificationEntity.NotificationType.valueOf(
                (type != null ? type : NotificationTypeDto.SYSTEM).name());
    }
}
