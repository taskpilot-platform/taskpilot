package com.taskpilot.contracts.aiquery.dto;

public record TaskDetailDto(
        Long id,
        Long projectId,
        String title,
        String description,
        String status,
        String priority,
        Integer difficultyLevel,
        String requiredSkills,
        String startDate,
        String dueDate,
        String assigneeName,
        Long assigneeId,
        Long version,
        String projectTimezoneId
) {
    public TaskDetailDto(
            Long id,
            Long projectId,
            String title,
            String description,
            String status,
            String priority,
            Integer difficultyLevel,
            String requiredSkills,
            String dueDate,
            String assigneeName,
            Long assigneeId) {
        this(id, projectId, title, description, status, priority, difficultyLevel, requiredSkills,
                null, dueDate, assigneeName, assigneeId, 0L, null);
    }
}
