package com.taskpilot.ai.dto;

/**
 * Supported outcome types for Phase 2D task outcome evaluation.
 */
public enum TaskOutcomeType {
    COMPLETED_ON_TIME,
    COMPLETED_LATE,
    INCOMPLETE_OVERDUE,
    NOT_YET_OBSERVABLE,
    EXCLUDED
}
