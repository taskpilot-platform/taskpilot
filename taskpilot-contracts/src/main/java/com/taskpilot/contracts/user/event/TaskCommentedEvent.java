package com.taskpilot.contracts.user.event;

import com.taskpilot.contracts.user.dto.NotificationTypeDto;

public record TaskCommentedEvent(
        Long projectId,
        Long taskId,
        Long commentId,
        Long targetUserId,
        String title,
        String message,
        String linkAction,
        NotificationTypeDto type) {

    public TaskCommentedEvent(
            Long targetUserId,
            String title,
            String message,
            String linkAction,
            NotificationTypeDto type) {
        this(null, null, null, targetUserId, title, message, linkAction, type);
    }
}
