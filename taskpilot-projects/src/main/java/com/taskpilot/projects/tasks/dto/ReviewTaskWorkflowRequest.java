package com.taskpilot.projects.tasks.dto;

import com.taskpilot.contracts.task.ReviewDecision;

import java.util.UUID;

public record ReviewTaskWorkflowRequest(
        Long submissionId,
        Long expectedTaskVersion,
        UUID requestId,
        ReviewDecision decision,
        Integer qualityRating,
        String feedback,
        String evidenceSummary) {
}
