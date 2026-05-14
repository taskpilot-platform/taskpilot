package com.taskpilot.projects.sprints.dto;

import java.util.List;

import com.taskpilot.projects.tasks.dto.TaskDto;

public record BacklogResponse(
        List<TaskDto> unscheduledTasks,
        List<SprintBacklogSection> sprints) {
}
