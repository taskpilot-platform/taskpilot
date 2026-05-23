package com.taskpilot.projects.sprints.dto;

import java.time.LocalDate;

import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.SprintEntity.SprintStatus;

public record SprintDto(
        Long id,
        Long projectId,
        String name,
        String goal,
        SprintStatus status,
        LocalDate startDate,
        LocalDate endDate) {
    public static SprintDto fromEntity(SprintEntity entity) {
        return new SprintDto(
                entity.getId(),
                entity.getProjectId(),
                entity.getName(),
                entity.getGoal(),
                entity.getStatus(),
                entity.getStartDate(),
                entity.getEndDate());
    }
}
