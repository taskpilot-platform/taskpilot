package com.taskpilot.contracts.aiquery.dto;

import java.time.Instant;
import java.util.List;

public record TaskCommentSummaryDto(
        Long id,
        Long taskId,
        Long parentCommentId,
        Long authorId,
        String authorName,
        String content,
        List<Long> mentionedUserIds,
        Boolean deleted,
        Instant createdAt,
        Instant updatedAt) {
}
