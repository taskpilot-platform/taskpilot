package com.taskpilot.contracts.task;

import java.time.Instant;

public record TaskWorkflowResult(
        long taskId,
        long taskVersion,
        TaskWorkflowState state,
        Long submissionId,
        Long reviewId,
        Long evaluationId,
        Integer evaluationVersion,
        EvidenceStatus evidenceStatus,
        TimelinessStatus timelinessStatus,
        Instant submittedAt,
        Instant reviewedAt,
        Instant completedAt,
        Boolean onTime) {

    public TaskWorkflowResult(
            long taskId,
            long taskVersion,
            TaskWorkflowState state,
            Long submissionId,
            Long reviewId,
            Long evaluationId) {
        this(taskId, taskVersion, state, submissionId, reviewId, evaluationId,
                null, null, null, null, null, null, null);
    }
}
