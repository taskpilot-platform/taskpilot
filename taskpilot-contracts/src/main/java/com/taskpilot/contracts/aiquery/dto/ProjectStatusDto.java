package com.taskpilot.contracts.aiquery.dto;

public record ProjectStatusDto(
    Long projectId,
    String name,
    String status,
    long totalTasks,
    long doneTasks,
    long overdueTasks,
    long completionPercent
) {}
