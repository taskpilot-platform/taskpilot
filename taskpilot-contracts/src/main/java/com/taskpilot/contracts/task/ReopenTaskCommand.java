package com.taskpilot.contracts.task;

import java.util.UUID;

public record ReopenTaskCommand(
        long taskId,
        Long expectedTaskVersion,
        UUID requestId,
        String reason) {
}
