package com.taskpilot.contracts.task;

import java.util.UUID;

public record ReviewTaskCommand(
        long taskId,
        Long submissionId,
        Long expectedTaskVersion,
        UUID requestId,
        ReviewDecision decision,
        Integer qualityRating,
        String feedback,
        String evidenceSummary) {
}
