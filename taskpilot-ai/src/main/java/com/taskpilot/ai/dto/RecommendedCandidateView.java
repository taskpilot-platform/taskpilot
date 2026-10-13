package com.taskpilot.ai.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;

/**
 * Explicit allowlisted candidate recommendation presentation view.
 *
 * Enforces Decisions H-010, H-011, H-015, H-016, H-019, H-022:
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
public record RecommendedCandidateView(
        int rank,
        Long candidateId,
        String displayName,
        Double presentationFitValue,
        MetricDataStatus fitStatus,
        Integer storedWorkloadValue,
        MetricDataStatus workloadStatus,
        String workloadUnit,
        String workloadScope,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant workloadMeasuredAt,
        MetricDataStatus performanceStatus,
        String memberStatus
) {
}
