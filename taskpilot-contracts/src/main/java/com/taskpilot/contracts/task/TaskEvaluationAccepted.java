package com.taskpilot.contracts.task;

import java.time.Instant;
import java.util.UUID;

public record TaskEvaluationAccepted(
        UUID eventId,
        long taskId,
        long projectId,
        long assignmentVersion,
        long submissionId,
        long evaluationId,
        long evaluationVersion,
        long assigneeId,
        long reviewerId,
        Instant acceptedSubmittedAt,
        Instant reviewedAt,
        Instant commitmentDueAt,
        Integer qualityRating,
        Boolean onTime,
        String rubricVersion,
        String evidenceStatus,
        long evidenceRevision,
        String assignmentDecisionId) {
}
