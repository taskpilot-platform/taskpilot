package com.taskpilot.ai.service;

import com.taskpilot.ai.dto.TaskOutcomeType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Pure evaluator for Phase 2D task outcome status and deadline checks.
 * Deterministic evaluator: does not call Instant.now() internally.
 * Enforces production Instant dueDate comparison:
 * - completedAt <= dueDate -> COMPLETED_ON_TIME
 * - completedAt > dueDate  -> COMPLETED_LATE
 */
@Component
public class TaskOutcomeEvaluator {

    public static final String EXCLUSION_MISSING_DUE_DATE = "MISSING_DUE_DATE";
    public static final String EXCLUSION_MISSING_COMPLETION_TIMESTAMP = "MISSING_COMPLETION_TIMESTAMP";
    public static final String EXCLUSION_ASSIGNEE_MISMATCH = "ASSIGNEE_MISMATCH";

    public record EvaluationResult(
            TaskOutcomeType outcomeType,
            String exclusionReason
    ) {
    }

    public EvaluationResult evaluate(
            String taskStatus,
            Instant dueAt,
            Instant completedAt,
            Instant observedAt,
            Long observedAssigneeId,
            Long selectedCandidateId) {

        // Check 1: Assignee mismatch check according to H-023
        if (selectedCandidateId != null && !Objects.equals(selectedCandidateId, observedAssigneeId)) {
            return new EvaluationResult(TaskOutcomeType.EXCLUDED, EXCLUSION_ASSIGNEE_MISMATCH);
        }

        boolean isDone = "DONE".equalsIgnoreCase(taskStatus);

        if (isDone) {
            // Completed task without completion timestamp -> EXCLUDED
            if (completedAt == null) {
                return new EvaluationResult(TaskOutcomeType.EXCLUDED, EXCLUSION_MISSING_COMPLETION_TIMESTAMP);
            }
            // Completed task without due date -> EXCLUDED (missing data is not treated as late)
            if (dueAt == null) {
                return new EvaluationResult(TaskOutcomeType.EXCLUDED, EXCLUSION_MISSING_DUE_DATE);
            }
            // Both timestamps present: compare completedAt against dueAt
            if (completedAt.isAfter(dueAt)) {
                return new EvaluationResult(TaskOutcomeType.COMPLETED_LATE, null);
            } else {
                // completedAt <= dueAt (before or exactly at due date)
                return new EvaluationResult(TaskOutcomeType.COMPLETED_ON_TIME, null);
            }
        } else {
            // Task status != DONE
            if (dueAt != null && observedAt != null && observedAt.isAfter(dueAt)) {
                return new EvaluationResult(TaskOutcomeType.INCOMPLETE_OVERDUE, null);
            }
            return new EvaluationResult(TaskOutcomeType.NOT_YET_OBSERVABLE, null);
        }
    }
}
