package com.taskpilot.projects.tasks.dto;

import java.util.List;
import java.util.UUID;

import com.taskpilot.projects.common.enums.PriorityLevel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateTaskRequest(
        @NotNull(message = "Project ID is required") Long projectId,

        Long parentId,
        Long sprintId,

        @NotBlank(message = "Title is required") String title,

        String description,
        PriorityLevel priority,
        Float position,
        List<Long> labelIds,
        Integer difficultyLevel,
        List<Long> requiredSkillIds,
        Long assigneeId,
        String startDate,
        String dueDate,
        UUID requestId,
        Long successorOfTaskId) {
    public CreateTaskRequest(
            Long projectId,
            Long parentId,
            Long sprintId,
            String title,
            String description,
            PriorityLevel priority,
            Float position,
            List<Long> labelIds,
            Integer difficultyLevel,
            List<Long> requiredSkillIds,
            Long assigneeId,
            String startDate,
            String dueDate) {
        this(projectId, parentId, sprintId, title, description, priority, position, labelIds,
                difficultyLevel, requiredSkillIds, assigneeId, startDate, dueDate, null, null);
    }

    public CreateTaskRequest(
            Long projectId,
            Long parentId,
            Long sprintId,
            String title,
            String description,
            PriorityLevel priority,
            Float position,
            List<Long> labelIds,
            Integer difficultyLevel,
            List<Long> requiredSkillIds,
            Long assigneeId,
            String startDate,
            String dueDate,
            UUID requestId) {
        this(projectId, parentId, sprintId, title, description, priority, position, labelIds,
                difficultyLevel, requiredSkillIds, assigneeId, startDate, dueDate, requestId, null);
    }
}
