package com.taskpilot.contracts.task;

import java.util.UUID;

public record SupersedeEvaluationCommand(
        long taskId,
        Long submissionId,
        Long expectedEvaluationId,
        Long expectedEvaluationVersion,
        Long expectedTaskVersion,
        UUID requestId,
        Integer qualityRating,
        String reason,
        String evidenceSummary) {
}
