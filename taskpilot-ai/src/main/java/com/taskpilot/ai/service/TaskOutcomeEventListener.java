package com.taskpilot.ai.service;

import com.taskpilot.contracts.assignment.event.TaskCompletedLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Event listener that captures task outcomes when a task transitions to DONE.
 * Ordering: task is already persisted and committed in DONE status.
 * If outcome persistence fails, the failure is caught and logged under TD-P2D-OUTCOME-PERSISTENCE-AFTER-TASK-COMPLETION
 * so that the task completion itself is never rolled back or failed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskOutcomeEventListener {

    private final TaskOutcomeService taskOutcomeService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCompleted(TaskCompletedLifecycleEvent event) {
        if (event == null || event.taskId() == null) {
            return;
        }
        try {
            taskOutcomeService.recordOutcomeForCompletedTask(
                    event.taskId(),
                    event.projectId(),
                    event.assigneeId(),
                    event.completedAt(),
                    event.dueDate()
            );
        } catch (Exception e) {
            log.error("TD-P2D-OUTCOME-PERSISTENCE-AFTER-TASK-COMPLETION: Unexpected error handling task completion event for taskId={}: {}",
                    event.taskId(), e.getMessage(), e);
        }
    }
}
