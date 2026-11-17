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
        String startDate,
        String dueDate,
        String createdAt,
        String updatedAt,
        Long version,
        String projectTimezoneId
) {
    public TaskSummaryDto(
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
        this(id, projectId, parentId, sprintId, title, description, status, priority, difficultyLevel,
                assigneeId, assigneeName, null, dueDate, createdAt, updatedAt, 0L, null);
    }
}
