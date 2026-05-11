package com.taskpilot.contracts.user.dto;

public record SystemNotificationCommandDto(Long targetUserId, String title, String message, String linkAction) {
}
