package com.taskpilot.ai.dto;

import java.time.Instant;

/**
 * Read-only DTO for task outcome records in Phase 2D.
 * Excludes all PII, prompts, internal ranking scores, and LLM output.
 */
public record TaskOutcomeDto(
        String outcomeId,
        String snapshotId,
        String decisionId,
        Long projectId,
        Long taskId,
        Long recommendedCandidateId,
        Long selectedCandidateId,
        RecommendationDecisionType decisionType,
        RecommendationDecisionSource decisionSource,
        Long observedAssigneeId,
        String taskStatus,
        Instant dueAt,
        Instant completedAt,
        TaskOutcomeType outcomeType,
        String exclusionReason,
        Instant observedAt,
        String outcomeVersion
) {
}
