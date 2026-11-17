package com.taskpilot.projects.tasks.dto;

import com.taskpilot.contracts.task.EvidenceStatus;
import com.taskpilot.projects.common.enums.TaskStatus;

import java.time.Instant;

public record TombstonedTaskAuditDto(
        Long taskId,
        Long projectId,
        Long parentId,
        Long sprintId,
        String title,
        TaskStatus status,
        Long assigneeId,
        EvidenceStatus completionEvidenceStatus,
        Long currentSubmissionId,
        Long currentEvaluationId,
        Instant deletedAt,
        Long deletedByUserId,
        String deletionReason
) {
}
