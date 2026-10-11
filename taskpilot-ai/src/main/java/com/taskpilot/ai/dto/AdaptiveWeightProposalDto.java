package com.taskpilot.ai.dto;

import java.time.Instant;

/**
 * Immutable read-only representation of an offline Adaptive Weight Proposal.
 * Governed by Phase 2F: approval does NOT mutate active production weights.
 */
public record AdaptiveWeightProposalDto(
        String proposalId,
        WeightProposalStatus status,
        String targetHeuristicMode,
        String datasetVersion,
        String datasetHash,
        int datasetSampleCount,
        double currentFitWeight,
        double currentLoadWeight,
        double currentPerformanceWeight,
        Double currentAdaptiveFitWeight,
        Double currentAdaptiveLoadWeight,
        Double proposedFitWeight,
        Double proposedLoadWeight,
        Double proposedPerformanceWeight,
        Double gridStep,
        Double currentMetricHistoricalAgreement,
        Double currentMetricOnTimeAssociation,
        Double metricHistoricalAgreement,
        Double metricOnTimeAssociation,
        Instant evaluationWindowStart,
        Instant evaluationWindowEnd,
        String algorithmVersion,
        String proposalVersion,
        Instant createdAt,
        Long createdByUserId,
        Instant reviewedAt,
        Long reviewedByUserId,
        String reviewNotes,
        String rejectionReason
) {
}
