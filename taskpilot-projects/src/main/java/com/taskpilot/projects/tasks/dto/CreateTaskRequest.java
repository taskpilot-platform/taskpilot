package com.taskpilot.projects.tasks.dto;

import java.time.Instant;
import java.util.List;

import com.taskpilot.projects.common.entity.TaskEntity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateTaskRequest(
    @NotNull(message = "Project ID is required")
    Long projectId,
    
    Long parentId,
    Long sprintId,
    
    @NotBlank(message = "Title is required")
    String title,
    
    String description,
    TaskEntity.PriorityLevel priority,
    Float position,
    List<String> tags,
    Integer difficultyLevel,
    List<String> requiredSkills,
    Long assigneeId,
    Instant startDate,
    Instant dueDate
) {}
