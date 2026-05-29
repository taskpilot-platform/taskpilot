package com.taskpilot.contracts.aiquery.dto;

public record SprintSummaryDto(
        Long id,
        Long projectId,
        String name,
        String goal,
        String status,
        String startDate,
        String endDate,
        String heuristicMode) {
}
