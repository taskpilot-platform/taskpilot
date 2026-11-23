package com.taskpilot.ai.dto;

import java.time.Instant;

public record ChosenTaskOutcomeDiagnosticsDto(
        String outcomeId,
        long evaluationId,
        int evaluationVersion,
        String evidenceStatus,
        Integer qualityRating,
        String rubricVersion,
        Boolean onTime,
        String timelinessStatus,
        Instant acceptedSubmittedAt,
        Instant reviewedAt,
        Instant commitmentDueAt
) {
}
