package com.taskpilot.contracts.task;

import java.util.List;

/**
 * Structured workflow boundary conflict (HTTP 409) when a general status/kanban route attempts
 * a workflow-gated transition (e.g., to REVIEW/DONE or reopening DONE).
 */
public class TaskWorkflowRequiredException extends RuntimeException {

    private final String requiredAction;
    private final List<String> allowedActions;

    public TaskWorkflowRequiredException(String requiredAction, String message, List<String> allowedActions) {
        super(message);
        this.requiredAction = requiredAction;
        this.allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
    }

    public String getRequiredAction() {
        return requiredAction;
    }

    public List<String> getAllowedActions() {
        return allowedActions;
    }
}
