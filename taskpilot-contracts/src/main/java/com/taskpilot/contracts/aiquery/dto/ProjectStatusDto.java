package com.taskpilot.contracts.aiquery.dto;

public record ProjectStatusDto(
    Long projectId,
    String name,
    String status,
    long totalTasks,
    long completedTasks,
    long overdueTasks,
    long completionPercent
) {}
