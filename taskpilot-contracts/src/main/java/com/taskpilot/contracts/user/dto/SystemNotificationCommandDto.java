package com.taskpilot.contracts.user.dto;

public record SystemNotificationCommandDto(Long targetUserId, String title, String message,
        String linkAction, NotificationTypeDto type) {

    public SystemNotificationCommandDto(Long targetUserId, String title, String message, String linkAction) {
        this(targetUserId, title, message, linkAction, NotificationTypeDto.SYSTEM);
    }

    public SystemNotificationCommandDto {
        if (type == null) {
            type = NotificationTypeDto.SYSTEM;
        }
    }
}
