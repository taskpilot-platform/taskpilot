package com.taskpilot.projects.tasks.dto;

import java.util.UUID;

public record UpdateTaskSprintRequest(
        Long sprintId,
        Long expectedTaskVersion,
        UUID requestId
) {
    public UpdateTaskSprintRequest(Long sprintId) {
        this(sprintId, null, null);
    }
}
