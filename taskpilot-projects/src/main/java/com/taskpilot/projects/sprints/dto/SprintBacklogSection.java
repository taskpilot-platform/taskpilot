package com.taskpilot.projects.sprints.dto;

import java.util.List;

import com.taskpilot.projects.tasks.dto.TaskDto;

public record SprintBacklogSection(
        SprintDto sprint,
        List<TaskDto> tasks) {
}
