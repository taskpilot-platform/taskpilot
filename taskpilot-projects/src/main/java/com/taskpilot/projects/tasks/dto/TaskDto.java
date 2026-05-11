package com.taskpilot.projects.tasks.dto;

import java.time.Instant;
import java.util.List;

import com.taskpilot.projects.common.entity.TaskEntity;

public record TaskDto(Long id, Long projectId, Long parentId, Long sprintId, String title,
        String description, TaskEntity.TaskStatus status, TaskEntity.PriorityLevel priority,
        Float position, List<LabelDto> labels, Integer difficultyLevel, Long assigneeId,
        Long reporterId, Instant startDate, Instant dueDate, Instant createdAt, Instant updatedAt) {
    public static TaskDto fromEntity(TaskEntity entity, List<LabelDto> labels) {
        return new TaskDto(entity.getId(), entity.getProjectId(), entity.getParentId(),
                entity.getSprintId(), entity.getTitle(), entity.getDescription(),
                entity.getStatus(), entity.getPriority(), entity.getPosition(), labels,
                entity.getDifficultyLevel(), entity.getAssigneeId(), entity.getReporterId(),
                entity.getStartDate(), entity.getDueDate(), entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
