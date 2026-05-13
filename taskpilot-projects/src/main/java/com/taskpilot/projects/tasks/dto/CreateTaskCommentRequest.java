package com.taskpilot.projects.tasks.dto;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;

public record CreateTaskCommentRequest(
        @NotBlank(message = "Comment content is required")
        String content,
        Long parentCommentId,
        Set<Long> mentionedUserIds) {
}
