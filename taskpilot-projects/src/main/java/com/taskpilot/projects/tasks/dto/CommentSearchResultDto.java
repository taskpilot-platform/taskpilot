package com.taskpilot.projects.tasks.dto;

import java.time.Instant;
import java.util.List;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;

public record CommentSearchResultDto(
        Long id,
        Long projectId,
        String projectName,
        Long taskId,
        String taskTitle,
        Long parentCommentId,
        UserProfileLiteDto author,
        String content,
        List<UserProfileLiteDto> mentions,
        Boolean deleted,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt) {
}
