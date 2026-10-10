package com.taskpilot.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

/**
 * Allowlisted candidate recommendation presentation view.
 *
 * Enforces Decisions H-010, H-011, H-014, H-015, H-016:
 * Presentation boundary between internal heuristic ranking state and user-facing payloads.
 * Exposes ONLY approved presentation fields. Strictly omits:
 * - internal totalScore & display-rounded totalScore
 * - normalized criteria (fitScore, loadScore, performanceScore)
 * - redundant scores (skillScore, workloadScore)
 * - confidenceScore
 * - email (PII)
 * - numeric performance score (uncalibrated prior/decay)
 * - weights and internal comparator details
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecommendationView(
        Long projectId,
        List<String> requiredSkills,
        List<RecommendedCandidateView> candidates,
        RecommendationDifferentiationStatus differentiationStatus,
        String presentationContractVersion,
        String scoringModelVersion,
        String heuristicMode,
        String aiExplanation
) {
    public static final String PRESENTATION_CONTRACT_VERSION = "allowlisted-view-v1";
    public static final String SCORING_MODEL_VERSION = "relative-neutral-fixed-point-v2";

    public RecommendationView {
        if (presentationContractVersion == null) {
            presentationContractVersion = PRESENTATION_CONTRACT_VERSION;
        }
        if (scoringModelVersion == null) {
            scoringModelVersion = SCORING_MODEL_VERSION;
        }
        if (differentiationStatus == null) {
            differentiationStatus = RecommendationDifferentiationStatus.UNKNOWN;
        }
    }

}
