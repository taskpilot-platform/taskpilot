package com.taskpilot.projects.tasks.dto;

import com.taskpilot.projects.common.enums.TaskStatus;

import jakarta.validation.constraints.NotNull;

public record KanbanMoveRequest(
    @NotNull(message = "Status is required")
    TaskStatus status,
    
    @NotNull(message = "Position is required")
    Float position
) {}
