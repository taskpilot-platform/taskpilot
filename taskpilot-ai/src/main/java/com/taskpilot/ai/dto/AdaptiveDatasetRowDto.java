package com.taskpilot.ai.dto;

import java.util.List;

public record AdaptiveDatasetRowDto(
        String rowId,
        String snapshotId,
        String decisionId,
        Long taskId,
        Long projectId,
        Long managerId,
        String heuristicMode,
        String scoringModelVersion,
        String presentationContractVersion,
        String weightVersion,
        Long selectedCandidateId,
        Long recommendedCandidateId,
        RecommendationDecisionType decisionType,
        double selectedCandidateFit,
        long selectedCandidateLoad,
        double selectedCandidatePerformance,
        String selectedCandidatePerformanceStatus,
        List<CandidateFeatureDto> candidates,
        ChosenTaskOutcomeDiagnosticsDto outcomeDiagnostics
) {
}
