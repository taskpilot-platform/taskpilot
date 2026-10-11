package com.taskpilot.ai.dto;

/**
 * Normalized evidence features for a candidate in an eligible Adaptive dataset sample.
 * Allows deterministic offline re-ranking during Phase 2F shadow proposal evaluation.
 */
public record CandidateFeatureDto(
        Long candidateId,
        double rawFit,
        double rawLoad,
        boolean isRecommended,
        boolean isSelected
) {
}
