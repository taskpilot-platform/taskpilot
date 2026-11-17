package com.taskpilot.projects.tasks.dto;

import java.util.UUID;

public record WithdrawTaskWorkflowRequest(
        Long submissionId,
        Long expectedTaskVersion,
        UUID requestId,
        String reason) {
}
