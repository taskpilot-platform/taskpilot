package com.taskpilot.projects.projects.dto;

public record ProjectSummaryResponse(
        Long projectId,
        String projectName,
        long totalMembers,
        long totalTasks,
        long todoTasks,
        long inProgressTasks,
        long reviewTasks,
        long doneTasks,
        double completionRate
) {
}
