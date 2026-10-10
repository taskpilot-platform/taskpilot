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
        String message) {
}
