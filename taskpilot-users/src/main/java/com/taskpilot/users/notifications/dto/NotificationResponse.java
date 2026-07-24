package com.taskpilot.users.notifications.dto;

import java.time.Instant;

import com.taskpilot.users.common.entity.NotificationEntity;
import com.taskpilot.users.common.enums.NotificationType;

public record NotificationResponse(
        Long id,
        Long userId,
        String title,
        String message,
        NotificationType type,
        Boolean isRead,
        String linkAction,
        Instant createdAt
        ) {

    public static NotificationResponse fromEntity(NotificationEntity entity) {
        return new NotificationResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getTitle(),
                entity.getMessage(),
                entity.getType(),
                entity.getIsRead(),
                entity.getLinkAction(),
                entity.getCreatedAt());
    }
}
