package com.taskpilot.projects.tasks.dto;

import java.util.List;
import java.util.UUID;

import com.taskpilot.projects.common.enums.PriorityLevel;
import com.taskpilot.projects.common.enums.TaskStatus;

public record UpdateTaskRequest(
    Long expectedTaskVersion,
    UUID requestId,
    String title,
    String description,
    TaskStatus status,
    PriorityLevel priority,
    Float position,
    List<Long> labelIds,
    Integer difficultyLevel,
    List<Long> requiredSkillIds,
    Long assigneeId,
    String startDate,
    String dueDate,
    Long sprintId
) {}
