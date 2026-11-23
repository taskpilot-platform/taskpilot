package com.taskpilot.contracts.task;

import java.util.UUID;

public record RecoverLegacyReviewCommand(
        long taskId,
        Long expectedTaskVersion,
        UUID requestId,
        String reason) {
}
