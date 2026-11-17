package com.taskpilot.contracts.task;

import java.time.Instant;
import java.util.UUID;

public record TaskEvaluationInvalidated(
        UUID eventId,
        long taskId,
        long projectId,
        long evaluationId,
        long evaluationVersion,
        String reason,
        Instant occurredAt,
        long evidenceRevision) {
}
