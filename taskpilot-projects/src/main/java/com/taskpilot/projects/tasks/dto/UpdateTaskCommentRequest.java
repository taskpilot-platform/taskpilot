package com.taskpilot.projects.tasks.dto;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;

public record UpdateTaskCommentRequest(
        @NotBlank(message = "Comment content is required")
        String content,
        Set<Long> mentionedUserIds) {
}
