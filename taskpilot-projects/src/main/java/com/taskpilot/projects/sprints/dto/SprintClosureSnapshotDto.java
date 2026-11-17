package com.taskpilot.projects.sprints.dto;

import com.taskpilot.projects.common.entity.SprintClosureSnapshotEntity;

import java.time.Instant;
import java.util.UUID;

public record SprintClosureSnapshotDto(
        Long id,
        Long sprintId,
        Long projectId,
        String sprintName,
        Long sprintVersionAtClose,
        String carryoverDestination,
        Long targetSprintId,
        int totalTasksAtClose,
        int doneTasksRetained,
        int carriedOverTotal,
        int carriedOverTodo,
        int carriedOverInProgress,
        int carriedOverReview,
        String snapshotPayloadJson,
        Long closedByUserId,
        Instant closedAt,
        UUID requestId
) {
    public static SprintClosureSnapshotDto fromEntity(SprintClosureSnapshotEntity entity) {
        if (entity == null) {
            return null;
        }
        return new SprintClosureSnapshotDto(
                entity.getId(),
                entity.getSprintId(),
                entity.getProjectId(),
                entity.getSprintName(),
                entity.getSprintVersionAtClose(),
                entity.getCarryoverDestination(),
                entity.getTargetSprintId(),
                entity.getTotalTasksAtClose(),
                entity.getDoneTasksRetained(),
                entity.getCarriedOverTotal(),
                entity.getCarriedOverTodo(),
                entity.getCarriedOverInProgress(),
                entity.getCarriedOverReview(),
                entity.getSnapshotPayloadJson(),
                entity.getClosedByUserId(),
                entity.getClosedAt(),
                entity.getRequestId());
    }
}
