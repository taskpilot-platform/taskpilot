package com.taskpilot.projects.sprints.dto;

import java.util.List;

import com.taskpilot.projects.common.enums.WorkflowMode;
import com.taskpilot.projects.tasks.dto.TaskDto;

public record BoardResponse(
        WorkflowMode workflowMode,
        SprintDto activeSprint,
        List<TaskDto> tasks) {
}
