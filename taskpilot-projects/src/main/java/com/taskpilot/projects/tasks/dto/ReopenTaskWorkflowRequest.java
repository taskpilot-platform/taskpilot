package com.taskpilot.projects.tasks.dto;

import java.util.UUID;

public record ReopenTaskWorkflowRequest(
        Long expectedTaskVersion,
        UUID requestId,
        String reason) {
}
