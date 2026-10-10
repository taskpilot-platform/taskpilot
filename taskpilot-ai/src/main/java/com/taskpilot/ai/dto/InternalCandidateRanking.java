package com.taskpilot.ai.dto;

import com.taskpilot.ai.heuristic.NormalizedScores;
import lombok.Builder;

import java.util.Comparator;

/**
 * Internal ranking representation for candidate evaluation in AutoAssignmentService.
 *
 * Enforces Decisions H-001, H-010, H-011, H-012, H-013:
 * Preserves internal full-precision scores, fixed-point 1e9 ranking key,
 * normalized criteria, and raw metrics while strictly preventing leakage to user-facing payloads.
 */
@Builder
public record InternalCandidateRanking(
        Long userId,
        String fullName,
        String email,
        double rankingRawFit,
        Double presentationFitValue,
        int storedWorkloadValue,
        double derivedPerformanceInput,
        NormalizedScores normalizedScores,
        double fullPrecisionScore,
        long rankingKey,
        double roundedScore,
        double confidence,
        String status,
        String heuristicMode,
        MetricDataStatus fitStatus,
        MetricDataStatus workloadStatus,
        MetricDataStatus performanceStatus
) {
    public static final Comparator<InternalCandidateRanking> STEP_A_COMPARATOR = Comparator
            .<InternalCandidateRanking>comparingLong(InternalCandidateRanking::rankingKey).reversed()
            .thenComparing(Comparator.comparingDouble(InternalCandidateRanking::rankingRawFit).reversed())
            .thenComparingLong(InternalCandidateRanking::userId);

    public RecommendedCandidateView toView(int rank) {
        return RecommendedCandidateView.builder()
                .rank(rank)
                .candidateId(userId)
                .displayName(fullName)
                .presentationFitValue(presentationFitValue)
                .fitStatus(fitStatus)
                .storedWorkloadValue(storedWorkloadValue)
                .workloadStatus(workloadStatus)
                .performanceStatus(performanceStatus)
                .memberStatus(status)
                .build();
    }
}
