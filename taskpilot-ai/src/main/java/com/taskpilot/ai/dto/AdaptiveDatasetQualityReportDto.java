package com.taskpilot.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AdaptiveDatasetQualityReportDto(
        String datasetVersion,
        AdaptiveReportScope scope,
        long totalOutcomesEvaluated,
        long eligibleSamplesCount,
        long excludedSamplesCount,
        long manualEvidenceCount,
        long selfAttestedEvidenceCount,
        long rejectedFeedbackCount,
        long invalidatedOutcomesCount,
        long legacyOutcomeCount,
        long syntheticRowsCount,
        long pendingJournalCount,
        long failedJournalCount,
        Map<String, Long> exclusionBreakdown,
        String canonicalDatasetSha256,
        boolean sufficientEvidenceForProposal,
        int minimumRequiredSamples,
        String readinessStatus,
        String readinessMessage,
        Instant generatedAt,
        List<AdaptiveDatasetRowDto> sampleRows
) {
    public static final String DATASET_VERSION = "dataset-v2";
    public static final int DEFAULT_MINIMUM_REQUIRED_SAMPLES = 20;

    public record AdaptiveReportScope(
            Long projectId,
            Long managerId,
            String heuristicMode,
            int page,
            int limit
    ) {
    }
}
