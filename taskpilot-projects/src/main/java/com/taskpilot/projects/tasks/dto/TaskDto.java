package com.taskpilot.projects.tasks.dto;

import java.time.Instant;
import java.util.List;

import com.taskpilot.contracts.task.EvidenceStatus;
import com.taskpilot.contracts.task.TimelinessStatus;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.PriorityLevel;
import com.taskpilot.projects.common.enums.TaskStatus;

public record TaskDto(
        Long id,
        Long projectId,
        Long parentId,
        Long sprintId,
        String title,
        String description,
        TaskStatus status,
        PriorityLevel priority,
        Float position,
        List<LabelDto> labels,
        Integer difficultyLevel,
        Long assigneeId,
        Long reporterId,
        Instant startDate,
        Instant dueDate,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Long version,
        Long assignmentVersion,
        String projectTimezoneId,
        Long currentSubmissionId,
        Long currentEvaluationId,
        EvidenceStatus completionEvidenceStatus,
        Boolean legacyReviewMissingSubmission,
        Integer currentQualityRating,
        Instant currentSubmittedAt,
        Instant currentReviewedAt,
        Boolean currentOnTime,
        TimelinessStatus currentTimelinessStatus,
        Long successorOfTaskId
) {
    public static TaskDto fromEntity(TaskEntity entity, List<LabelDto> labels) {
        return fromEntity(entity, labels, null);
    }

    public static TaskDto fromEntity(TaskEntity entity, List<LabelDto> labels, String projectTimezoneId) {
        return fromEntity(entity, labels, projectTimezoneId, null, null, null, null, null);
    }

    public static TaskDto fromEntity(
            TaskEntity entity,
            List<LabelDto> labels,
            String projectTimezoneId,
            Integer currentQualityRating,
            Instant currentSubmittedAt,
            Instant currentReviewedAt,
            Boolean currentOnTime,
            TimelinessStatus currentTimelinessStatus) {
        boolean legacyMissingSub = entity.getStatus() == TaskStatus.REVIEW
                && entity.getCurrentSubmissionId() == null;
        return new TaskDto(
                entity.getId(),
                entity.getProjectId(),
                entity.getParentId(),
                entity.getSprintId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getPriority(),
                entity.getPosition(),
                labels,
                entity.getDifficultyLevel(),
                entity.getAssigneeId(),
                entity.getReporterId(),
                entity.getStartDate(),
                entity.getDueDate(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCompletedAt(),
                entity.effectiveVersion(),
                entity.effectiveAssignmentVersion(),
                projectTimezoneId,
                entity.getCurrentSubmissionId(),
                entity.getCurrentEvaluationId(),
                entity.getCompletionEvidenceStatus(),
                legacyMissingSub,
                currentQualityRating,
                currentSubmittedAt,
                currentReviewedAt,
                currentOnTime,
                currentTimelinessStatus,
                entity.getSuccessorOfTaskId());
    }
}
