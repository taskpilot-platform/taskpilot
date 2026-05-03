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
    String dueDate,
    String assigneeName,
    Long assigneeId
) {}
