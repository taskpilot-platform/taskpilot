package com.taskpilot.projects.projects.dto;

public record ProjectSummaryResponse(
        Long projectId,
        String projectName,
        long totalMembers,
        long totalTasks,
        long completedTasks,
        long inProgressTasks,
        long pendingTasks,
        double completionRate
) {
}
