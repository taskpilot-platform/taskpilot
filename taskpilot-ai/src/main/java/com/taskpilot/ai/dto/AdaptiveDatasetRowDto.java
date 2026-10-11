package com.taskpilot.ai.dto;

import java.time.Instant;
import java.util.List;

/**
 * Deterministic, privacy-safe evidence row in the Phase 2E Adaptive dataset.
 * Links: snapshot -> USER PM decision -> task -> observed final completion outcome.
 * Excludes all PII, prompts, internal full-precision scores, and default performance features.
 */
public record AdaptiveDatasetRowDto(
        String rowId,
        String snapshotId,
        String decisionId,
        Long taskId,
        Long projectId,
        Long selectedCandidateId,
        Long recommendedCandidateId,
        RecommendationDecisionType decisionType,
        TaskOutcomeType outcomeType,
        boolean completedOnTime,
        double selectedCandidateFit,
        double selectedCandidateLoad,
        List<CandidateFeatureDto> candidates,
        Instant observedAt
) {
    public AdaptiveDatasetRowDto(
            String rowId,
            String snapshotId,
            String decisionId,
            Long taskId,
            Long projectId,
            Long selectedCandidateId,
            Long recommendedCandidateId,
            RecommendationDecisionType decisionType,
            TaskOutcomeType outcomeType,
            boolean completedOnTime,
            double selectedCandidateFit,
            double selectedCandidateLoad,
            List<CandidateFeatureDto> candidates
    ) {
        this(rowId, snapshotId, decisionId, taskId, projectId, selectedCandidateId,
                recommendedCandidateId, decisionType, outcomeType, completedOnTime,
                selectedCandidateFit, selectedCandidateLoad, candidates, null);
    }
}
