package com.taskpilot.projects.tasks.dto;

import java.time.Instant;
import java.util.List;

import com.taskpilot.projects.common.enums.PriorityLevel;
import com.taskpilot.projects.common.enums.TaskStatus;

public record UpdateTaskRequest(
    String title,
    String description,
    TaskStatus status,
    PriorityLevel priority,
    Float position,
    List<Long> labelIds,
    Integer difficultyLevel,
    List<Long> requiredSkillIds,
    Long assigneeId,
    Instant startDate,
    Instant dueDate
) {}
