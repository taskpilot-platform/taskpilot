package com.taskpilot.contracts.user.dto;

public record SystemNotificationCommandDto(
        Long targetUserId,
        String title,
        String message,
        String linkAction,
        NotificationTypeDto type,
        Long projectId,
        Long taskId) {

    public SystemNotificationCommandDto(Long targetUserId, String title, String message, String linkAction) {
        this(targetUserId, title, message, linkAction, NotificationTypeDto.SYSTEM, null, null);
    }

    public SystemNotificationCommandDto(
            Long targetUserId,
            String title,
            String message,
            String linkAction,
            NotificationTypeDto type) {
        this(targetUserId, title, message, linkAction, type, null, null);
    }

    public SystemNotificationCommandDto {
        if (type == null) {
            type = NotificationTypeDto.SYSTEM;
        }
    }
}
