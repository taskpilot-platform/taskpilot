package com.taskpilot.ai.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Machine-readable quality report for Phase 2E Adaptive dataset.
 * Tracks total evidence analyzed, eligible count, exclusion breakdown, and canonical SHA-256 hash.
 */
public record AdaptiveDatasetQualityReportDto(
        long totalOutcomesEvaluated,
        long eligibleSamplesCount,
        long excludedSamplesCount,
        Map<String, Long> exclusionBreakdown,
        String canonicalDatasetSha256,
        boolean sufficientEvidenceForProposal,
        int minimumRequiredSamples,
        Instant generatedAt
) {
    public static final int DEFAULT_MINIMUM_REQUIRED_SAMPLES = 20;
}
