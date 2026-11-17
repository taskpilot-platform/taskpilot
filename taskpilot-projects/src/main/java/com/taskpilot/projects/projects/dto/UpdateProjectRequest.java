package com.taskpilot.projects.projects.dto;

import com.taskpilot.projects.common.enums.HeuristicMode;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.enums.WorkflowMode;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateProjectRequest(
        @Size(max = 255, message = "Project name must not exceed 255 characters")
        String name,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        ProjectStatus status,

        HeuristicMode heuristicMode,

        WorkflowMode workflowMode,

        LocalDate startDate,

        LocalDate endDate,

        @Size(max = 64, message = "Timezone ID must not exceed 64 characters")
        String timezoneId
) {
    public UpdateProjectRequest(
            String name,
            String description,
            ProjectStatus status,
            HeuristicMode heuristicMode,
            WorkflowMode workflowMode,
            LocalDate startDate,
            LocalDate endDate) {
        this(name, description, status, heuristicMode, workflowMode, startDate, endDate, null);
    }
}
