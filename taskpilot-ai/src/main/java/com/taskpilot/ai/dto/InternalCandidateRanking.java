package com.taskpilot.ai.dto;

import lombok.Builder;

import java.time.Instant;

@Builder
public record InternalCandidateRanking(
        Long userId,
        String fullName,
        double rankingRawFit,
        Double presentationFitValue,
        MetricDataStatus fitStatus,
        String fitSource,
        int storedWorkloadValue,
        MetricDataStatus workloadStatus,
        String workloadUnit,
        String workloadScope,
        Instant workloadMeasuredAt,
        String workloadRevision,
        double derivedPerformanceInput,
        MetricDataStatus performanceStatus,
        String performanceVersion,
        String performanceRubricVersion,
        Integer performanceSampleCount,
        Integer performanceWindowLimit,
        Double observedQualityMean,
        Double evidenceWeight,
        String performanceEvidenceRevision,
        Instant performanceMeasuredAt,
        double fullPrecisionScore,
        long rankingKey,
        String memberStatus,
        String heuristicMode
) {
}
