package com.taskpilot.contracts.task;

import java.util.UUID;

public record WithdrawTaskCommand(
        long taskId,
        Long submissionId,
        Long expectedTaskVersion,
        UUID requestId,
        String reason) {
}
