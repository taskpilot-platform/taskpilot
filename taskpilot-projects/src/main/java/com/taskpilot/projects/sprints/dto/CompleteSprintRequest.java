package com.taskpilot.projects.sprints.dto;

import java.util.List;
import java.util.UUID;

public record CompleteSprintRequest(
        Long expectedSprintVersion,
        UUID requestId,
        CarryoverSelection carryover,
        List<Long> expectedUnfinishedTaskIds
) {
    public record CarryoverSelection(
            String destination,
            Long targetSprintId
    ) {
    }
}
