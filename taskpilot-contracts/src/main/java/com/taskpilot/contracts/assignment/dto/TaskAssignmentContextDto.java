package com.taskpilot.contracts.assignment.dto;

public record TaskAssignmentContextDto(
        long taskId,
        long projectId,
        long taskVersion,
        long assignmentVersion,
        String status,
        Long assigneeId,
        boolean deleted) {
}
