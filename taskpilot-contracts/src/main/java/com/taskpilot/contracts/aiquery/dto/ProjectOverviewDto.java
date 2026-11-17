package com.taskpilot.contracts.aiquery.dto;

public record ProjectOverviewDto(
        Long projectId,
        String name,
        String description,
        String status,
        String role,
        String startDate,
        String endDate,
        String joinedAt,
        String timezoneId
) {
    public ProjectOverviewDto(
            Long projectId,
            String name,
            String description,
            String status,
            String role,
            String startDate,
            String endDate,
            String joinedAt) {
        this(projectId, name, description, status, role, startDate, endDate, joinedAt, null);
    }
}
