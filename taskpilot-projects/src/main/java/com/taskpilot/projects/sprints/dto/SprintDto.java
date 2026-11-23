package com.taskpilot.projects.sprints.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.enums.SprintStatus;

public record SprintDto(
        Long id,
        Long projectId,
        String name,
        String goal,
        SprintStatus status,
        LocalDate startDate,
        LocalDate endDate,
        Long version,
        Instant completedAt,
        Long completedByUserId,
        SprintClosureSnapshotDto closureSnapshot) {
    public static SprintDto fromEntity(SprintEntity entity) {
        return fromEntity(entity, null);
    }

    public static SprintDto fromEntity(SprintEntity entity, SprintClosureSnapshotDto closureSnapshot) {
        return new SprintDto(
                entity.getId(),
                entity.getProjectId(),
                entity.getName(),
                entity.getGoal(),
                entity.getStatus(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.effectiveVersion(),
                entity.getCompletedAt(),
                entity.getCompletedByUserId(),
                closureSnapshot);
    }
}
