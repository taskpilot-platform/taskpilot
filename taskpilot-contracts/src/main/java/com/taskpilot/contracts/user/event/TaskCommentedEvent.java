package com.taskpilot.contracts.user.event;

import com.taskpilot.contracts.user.dto.NotificationTypeDto;

public record TaskCommentedEvent(Long targetUserId, String title, String message, String linkAction, NotificationTypeDto type) {
}
