package com.taskpilot.projects.tasks.dto;

import java.time.Instant;
import java.util.List;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;

public record TaskCommentDto(
        Long id,
        Long taskId,
        UserProfileLiteDto author,
        String content,
        List<UserProfileLiteDto> mentions,
        Instant createdAt,
        Instant updatedAt) {
}
