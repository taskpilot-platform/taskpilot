package com.taskpilot.contracts.task;

/**
 * Downstream projection/telemetry consumer invoked after domain transaction commit
 * from the durable task_workflow_journal outbox. Implementations must be idempotent
 * on eventId and may throw on transient failures to trigger retry without rolling back
 * already-committed task acceptance.
 */
public interface TaskEvaluationJournalConsumer {

    default void onEvaluationAccepted(TaskEvaluationAccepted event) {
    }

    default void onEvaluationInvalidated(TaskEvaluationInvalidated event) {
    }
}
