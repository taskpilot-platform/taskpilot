package com.taskpilot.contracts.user.dto;

import java.time.Instant;

public record NotificationSummaryDto(
        Long id,
        String title,
        String message,
        String type,
        Boolean isRead,
        String linkAction,
        Instant createdAt) {
}
