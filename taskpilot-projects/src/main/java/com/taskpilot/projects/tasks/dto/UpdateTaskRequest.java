package com.taskpilot.projects.tasks.dto;

import java.time.Instant;
import java.util.List;

import com.taskpilot.projects.common.entity.TaskEntity;

public record UpdateTaskRequest(
    String title,
    String description,
    TaskEntity.TaskStatus status,
    TaskEntity.PriorityLevel priority,
    Float position,
    List<String> tags,
    Integer difficultyLevel,
    List<String> requiredSkills,
    Long assigneeId,
    Instant startDate,
    Instant dueDate
) {}
