package com.taskpilot.projects.tasks.dto;

import java.util.UUID;

public record SupersedeEvaluationWorkflowRequest(
        Long submissionId,
        Long expectedEvaluationId,
        Long expectedEvaluationVersion,
        Long expectedTaskVersion,
        UUID requestId,
        Integer qualityRating,
        String reason,
        String evidenceSummary) {
}
