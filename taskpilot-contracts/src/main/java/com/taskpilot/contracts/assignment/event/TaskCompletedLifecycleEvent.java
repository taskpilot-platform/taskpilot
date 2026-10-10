package com.taskpilot.contracts.assignment.event;

import java.time.Instant;

/**
 * Event published when a task transitions to DONE status.
 * Used by downstream modules (e.g. AI outcome capture) to record task completion evidence.
 */
public record TaskCompletedLifecycleEvent(
        Long taskId,
        Long projectId,
        Long assigneeId,
        Instant completedAt,
        Instant dueDate
) {
}
