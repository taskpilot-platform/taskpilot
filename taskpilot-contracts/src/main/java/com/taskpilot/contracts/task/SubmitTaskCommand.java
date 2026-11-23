package com.taskpilot.contracts.task;

import java.util.List;
import java.util.UUID;

public record SubmitTaskCommand(
        long taskId,
        Long expectedTaskVersion,
        UUID requestId,
        String summary,
        List<Long> evidenceProjectFileIds) {

    public SubmitTaskCommand {
        evidenceProjectFileIds = evidenceProjectFileIds == null ? List.of() : List.copyOf(evidenceProjectFileIds);
    }
}
