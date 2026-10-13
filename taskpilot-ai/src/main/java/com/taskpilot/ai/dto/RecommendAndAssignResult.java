package com.taskpilot.ai.dto;

import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;

public record RecommendAndAssignResult(
        boolean assigned,
        Long taskId,
        Long projectId,
        Long selectedMemberId,
        String selectedMemberName,
        String reason,
        RecommendationView recommendation,
        TaskAssignmentResultDto assignment,
        String message,
        String snapshotId) {

    public RecommendAndAssignResult(
            boolean assigned,
            Long taskId,
            Long projectId,
            Long selectedMemberId,
            String selectedMemberName,
            String reason,
            RecommendationView recommendation,
            TaskAssignmentResultDto assignment,
            String message) {
        this(assigned, taskId, projectId, selectedMemberId, selectedMemberName, reason, recommendation, assignment, message, null);
    }
}
