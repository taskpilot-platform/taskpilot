package com.taskpilot.projects.tasks.dto;

import com.taskpilot.contracts.task.EvidenceStatus;
import com.taskpilot.contracts.task.TaskQualityRubric;
import com.taskpilot.contracts.task.TimelinessStatus;
import com.taskpilot.projects.common.enums.TaskStatus;

import java.time.Instant;
import java.util.List;

public record TaskWorkflowHistoryDto(
        long taskId,
        long projectId,
        long taskVersion,
        long assignmentVersion,
        long criteriaRevision,
        long dueCommitmentRevision,
        TaskStatus status,
        EvidenceStatus completionEvidenceStatus,
        Instant completedAt,
        Long currentSubmissionId,
        Long currentEvaluationId,
        boolean legacyReviewMissingSubmission,
        boolean otherActiveManagersExist,
        boolean canSubmit,
        boolean canWithdraw,
        boolean canReview,
        boolean canSelfAttest,
        boolean canReopen,
        boolean canSupersede,
        boolean canRecoverLegacyReview,
        String rubricVersion,
        List<TaskQualityRubric.LevelAnchor> rubric,
        List<SubmissionHistoryItem> submissions,
        List<ReviewHistoryItem> reviews,
        List<EvaluationHistoryItem> evaluations,
        List<DeadlineRevisionItem> deadlineRevisions) {

    public record EvidenceAvailabilityEventItem(
            long id,
            long evidenceRefId,
            long originalFileId,
            boolean available,
            String reason,
            Instant occurredAt,
            Long actorUserId) {
    }

    public record EvidenceFileItem(
            long evidenceRefId,
            Long liveSourceFileId,
            long originalFileId,
            String originalName,
            String contentType,
            long fileSize,
            String contentIdentity,
            Instant capturedAt,
            boolean available,
            String unavailableReason,
            List<EvidenceAvailabilityEventItem> availabilityEvents) {
    }

    public record SubmissionHistoryItem(
            long id,
            int cycleNo,
            long assignmentVersion,
            long submitterId,
            String submitterName,
            String summary,
            Instant submittedAt,
            Instant commitmentDueAt,
            long dueCommitmentRevision,
            long criteriaRevision,
            String projectTimezoneId,
            String status,
            Instant withdrawnAt,
            String withdrawalReason,
            Long withdrawnByUserId,
            Instant invalidatedAt,
            String invalidationReason,
            List<EvidenceFileItem> evidenceFiles) {
    }

    public record ReviewHistoryItem(
            long id,
            long submissionId,
            long reviewerId,
            String reviewerName,
            String decision,
            Integer qualityRating,
            String rubricVersion,
            String feedback,
            String evidenceSummary,
            boolean selfAttested,
            Long supersedesReviewId,
            Instant reviewedAt) {
    }

    public record EvaluationHistoryItem(
            long id,
            long submissionId,
            long reviewId,
            long assigneeId,
            String assigneeName,
            long reviewerId,
            String reviewerName,
            long assignmentVersion,
            String assignmentDecisionId,
            Integer qualityRating,
            EvidenceStatus evidenceStatus,
            String rubricVersion,
            Instant acceptedSubmittedAt,
            Instant reviewedAt,
            Instant originalCompletedAt,
            Instant commitmentDueAt,
            Boolean onTime,
            TimelinessStatus timelinessStatus,
            int evaluationVersion,
            Long supersedesEvaluationId,
            boolean invalidated,
            String invalidationReason,
            String invalidationDetailReason,
            Instant invalidatedAt,
            Long invalidatedByUserId,
            long evidenceRevision) {
    }

    public record DeadlineRevisionItem(
            long id,
            long revisionNo,
            Instant previousDueAt,
            Instant newDueAt,
            Long changedByUserId,
            String reason,
            Instant changedAt) {
    }
}
