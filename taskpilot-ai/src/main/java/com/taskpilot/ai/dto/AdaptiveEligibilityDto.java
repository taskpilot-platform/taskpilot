package com.taskpilot.ai.dto;

/**
 * Adaptive eligibility metadata evaluation record for Phase 2D/2E.
 * Captures eligibility criteria for future Adaptive dataset construction.
 */
public record AdaptiveEligibilityDto(
        boolean eligible,
        String reason,
        String snapshotId,
        String snapshotVersion,
        RecommendationDecisionType decisionType,
        RecommendationDecisionSource decisionSource,
        String workloadStatus,
        String workloadUnit,
        String workloadScope,
        String performanceStatus,
        TaskOutcomeType outcomeType,
        boolean hasCompletionTimestamp,
        boolean hasDueDate,
        boolean validLinkage
) {
}
