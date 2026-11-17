package com.taskpilot.projects.tasks.dto;

import java.util.List;
import java.util.UUID;

public record SubmitTaskWorkflowRequest(
        Long expectedTaskVersion,
        UUID requestId,
        String summary,
        List<Long> evidenceProjectFileIds) {
}
