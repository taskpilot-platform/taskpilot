package com.taskpilot.projects.projects.dto;

import com.taskpilot.projects.common.entity.ProjectEntity.HeuristicMode;
import com.taskpilot.projects.common.entity.ProjectEntity.ProjectStatus;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateProjectRequest(
        @Size(max = 255, message = "Project name must not exceed 255 characters")
        String name,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        ProjectStatus status,

        HeuristicMode heuristicMode,

        LocalDate startDate,

        LocalDate endDate
) {
}
