package com.taskpilot.contracts.aiquery.dto;

public record TaskSummaryDto(
        Long id,
        Long projectId,
        Long parentId,
        Long sprintId,
        String title,
        String description,
        String status,
        String priority,
        Integer difficultyLevel,
        Long assigneeId,
        String assigneeName,
        String dueDate,
        String createdAt,
        String updatedAt) {
}
