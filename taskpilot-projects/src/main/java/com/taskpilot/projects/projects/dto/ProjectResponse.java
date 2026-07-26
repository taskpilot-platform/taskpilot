package com.taskpilot.projects.projects.dto;

import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.enums.HeuristicMode;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.enums.WorkflowMode;

import java.time.Instant;
import java.time.LocalDate;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        ProjectStatus status,
        HeuristicMode heuristicMode,
        WorkflowMode workflowMode,
        LocalDate startDate,
        LocalDate endDate,
        Instant createdAt) {
    public static ProjectResponse fromEntity(ProjectEntity entity) {
        return new ProjectResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getHeuristicMode(),
                entity.getWorkflowMode(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getCreatedAt());
    }
}
