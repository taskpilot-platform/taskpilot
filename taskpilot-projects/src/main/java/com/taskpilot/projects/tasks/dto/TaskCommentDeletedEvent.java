package com.taskpilot.projects.tasks.dto;

public record TaskCommentDeletedEvent(Long taskId, Long commentId) {
}
