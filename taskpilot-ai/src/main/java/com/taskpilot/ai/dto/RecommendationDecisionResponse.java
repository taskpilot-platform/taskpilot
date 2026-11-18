package com.taskpilot.ai.dto;

import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;

import java.time.Instant;

public record RecommendationDecisionResponse(
        String decisionId,
        String snapshotId,
        Long projectId,
        Long taskId,
        Long decidedByUserId,
        RecommendationDecisionSource decisionSource,
        RecommendationDecisionType decisionType,
        Long recommendedCandidateId,
        Long selectedCandidateId,
        Long taskVersion,
        Long assignmentVersion,
        String reasonCode,
        String note,
        Instant createdAt,
        TaskAssignmentResultDto assignmentResult
) {
}
