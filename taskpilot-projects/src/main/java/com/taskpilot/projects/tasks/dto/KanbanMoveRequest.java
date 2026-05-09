package com.taskpilot.projects.tasks.dto;

import com.taskpilot.projects.common.entity.TaskEntity;

import jakarta.validation.constraints.NotNull;

public record KanbanMoveRequest(
    @NotNull(message = "Status is required")
    TaskEntity.TaskStatus status,
    
    @NotNull(message = "Position is required")
    Float position
) {}
