package com.taskpilot.projects.tasks.dto;

import java.util.UUID;

public record RecoverLegacyReviewWorkflowRequest(
        Long expectedTaskVersion,
        UUID requestId,
        String reason) {
}
