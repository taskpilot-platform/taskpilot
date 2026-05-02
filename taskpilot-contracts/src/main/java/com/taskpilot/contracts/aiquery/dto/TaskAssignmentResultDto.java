package com.taskpilot.contracts.aiquery.dto;

public record TaskAssignmentResultDto(
    Long taskId,
    Long assignedTo,
    String status,
    String reason,
    String errorMessage
) {
    public static TaskAssignmentResultDto success(Long taskId, Long assignedTo, String reason) {
        return new TaskAssignmentResultDto(taskId, assignedTo, "SUCCESS", reason, null);
    }

    public static TaskAssignmentResultDto failure(Long taskId, Long assignedTo, String reason, String errorMessage) {
        return new TaskAssignmentResultDto(taskId, assignedTo, "FAILURE", reason, errorMessage);
    }
}
