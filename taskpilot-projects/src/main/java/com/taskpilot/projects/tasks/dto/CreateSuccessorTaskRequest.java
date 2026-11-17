package com.taskpilot.projects.tasks.dto;

import com.taskpilot.projects.common.enums.PriorityLevel;

import java.util.List;
import java.util.UUID;

public record CreateSuccessorTaskRequest(
        String title,
        String description,
        PriorityLevel priority,
        Long sprintId,
        Integer difficultyLevel,
        List<Long> labelIds,
        List<Long> requiredSkillIds,
        Long assigneeId,
        String startDate,
        String dueDate,
        UUID requestId
) {
}
