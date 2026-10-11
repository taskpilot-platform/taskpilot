package com.taskpilot.ai.dto;

/**
 * Explicit reasons for excluding recommendation, decision, or outcome evidence
 * from the Adaptive learning dataset in Phase 2E.
 * Captures all 20+ distinct criteria required by Section 7 of the Adaptive contract.
 */
public enum AdaptiveExclusionReason {
    SNAPSHOT_NOT_FOUND("Linked recommendation snapshot was not found in the database"),
    UNSUPPORTED_SNAPSHOT_VERSION("Snapshot presentation contract version is not allowlisted-view-v2"),
    UNOBSERVED_SNAPSHOT("Snapshot differentiation status is UNOBSERVED"),
    DECISION_NOT_FOUND("Linked PM recommendation decision event was not found"),
    NON_USER_DECISION("PM decision source was not USER"),
    NON_ASSIGNMENT_DECISION("PM decision was not a terminal assignment decision (ACCEPTED or OVERRIDDEN)"),
    DECISION_TASK_MISMATCH("Decision taskId does not match the observed task"),
    DECISION_PROJECT_MISMATCH("Decision projectId does not match the snapshot or task"),
    SELECTED_CANDIDATE_NOT_FOUND("Selected candidate is missing from snapshot candidates list"),
    ASSIGNEE_MISMATCH("Observed task assignee does not match the selected candidate"),
    FIT_STATUS_NOT_MEASURED("Candidate Skill Fit status is not MEASURED"),
    INVALID_FIT_VALUE("Candidate Skill Fit value is null, non-finite, or outside [0.0, 1.0]"),
    WORKLOAD_STATUS_NOT_MEASURED("Candidate Workload status is not MEASURED"),
    UNSUPPORTED_WORKLOAD_UNIT("Candidate Workload unit is not ACTIVE_TASK_COUNT"),
    UNSUPPORTED_WORKLOAD_SCOPE("Candidate Workload scope is not PROJECT"),
    MISSING_WORKLOAD_TIMESTAMP("Candidate Workload measurement timestamp is missing"),
    INVALID_WORKLOAD_VALUE("Candidate Workload value is null or negative"),
    PERFORMANCE_FEATURE_EXCLUDED("Performance metric is DEFAULT baseline and excluded from adaptive tuning"),
    OUTCOME_NOT_FOUND("No linked task completion outcome record exists"),
    OUTCOME_NOT_COMPLETED("Task outcome status is not terminal completion (COMPLETED_ON_TIME or COMPLETED_LATE)"),
    OUTCOME_EXCLUDED("Task outcome was marked EXCLUDED by the outcome evaluator"),
    AMBIGUOUS_LINKAGE("Multiple matching decisions or outcomes exist for the task"),
    DUPLICATE_SAMPLE("Duplicate outcome record encountered for the decision-task pair"),
    REOPENED_TASK_EXCLUDED("Task was reopened after completion; historical outcome is excluded from adaptive training"),
    UNSUPPORTED_HEURISTIC_MODE("Snapshot heuristic mode is not BALANCED");

    private final String description;

    AdaptiveExclusionReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
