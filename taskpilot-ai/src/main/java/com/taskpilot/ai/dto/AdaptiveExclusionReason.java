package com.taskpilot.ai.dto;

public enum AdaptiveExclusionReason {
    SNAPSHOT_NOT_FOUND("Linked recommendation snapshot was not found"),
    UNSUPPORTED_SNAPSHOT_VERSION("Snapshot scoring or presentation version is not supported for dataset-v2"),
    UNOBSERVED_SNAPSHOT("Snapshot differentiation status is UNOBSERVED"),
    DECISION_NOT_FOUND("Linked PM recommendation decision event was not found"),
    NON_USER_DECISION("Decision source was not USER"),
    NON_ASSIGNMENT_DECISION("Decision was not an assignment choice (ACCEPTED or OVERRIDDEN)"),
    NO_COMPARABLE_CHOICE("REJECT-only feedback or single-candidate pool provides no comparable choice pair"),
    DECISION_TASK_MISMATCH("Decision taskId does not match the observed task"),
    DECISION_PROJECT_MISMATCH("Decision projectId does not match the snapshot or task"),
    ASSIGNMENT_VERSION_MISMATCH("Outcome assignmentVersion does not match the recorded decision assignmentVersion"),
    SELECTED_CANDIDATE_NOT_FOUND("Selected candidate is missing from snapshot candidates list"),
    ASSIGNEE_MISMATCH("Observed task assignee does not match the selected candidate"),
    FIT_STATUS_NOT_MEASURED("Candidate Skill Fit status is not MEASURED"),
    INVALID_FIT_VALUE("Candidate Skill Fit value is null, non-finite, or outside [0.0, 1.0]"),
    WORKLOAD_STATUS_NOT_MEASURED("Candidate Workload status is not MEASURED"),
    UNSUPPORTED_WORKLOAD_UNIT("Candidate Workload unit is not ACTIVE_TASK_COUNT"),
    UNSUPPORTED_WORKLOAD_SCOPE("Candidate Workload scope is not PROJECT"),
    MISSING_WORKLOAD_TIMESTAMP("Candidate Workload measurement timestamp is missing"),
    INVALID_WORKLOAD_VALUE("Candidate Workload value is null or negative"),
    INVALID_PERFORMANCE_INPUT("Candidate performance input or status is invalid"),
    POOL_CANDIDATE_INVALID("One or more comparison pool candidates failed feature/status/schema checks"),
    INSUFFICIENT_ALTERNATIVES("Comparison pool does not have at least one eligible alternative candidate"),
    OUTCOME_NOT_FOUND("No linked task outcome record exists"),
    OUTCOME_NOT_COMPLETED("Task outcome status is not a terminal completion"),
    OUTCOME_EXCLUDED("Task outcome was marked EXCLUDED"),
    REOPENED_TASK_EXCLUDED("Evaluation was invalidated because the task was reopened"),
    SUPERSEDED_EVALUATION_EXCLUDED("Evaluation was invalidated because a newer review superseded it"),
    RATING_CORRECTED_EXCLUDED("Evaluation was invalidated because the quality rating was corrected"),
    TASK_DELETED_EXCLUDED("Evaluation was invalidated because the task was deleted"),
    DUPLICATE_SAMPLE("Duplicate outcome or decision-task sample encountered"),
    UNSUPPORTED_HEURISTIC_MODE("Snapshot heuristic mode does not match requested scope mode"),
    SELF_ATTESTED_SEPARATED("Self-attested outcome is recorded separately and excluded from independent-review preference gate"),
    MANUAL_ASSIGNMENT_NO_DECISION("Manually assigned task without Adaptive decision creates no fabricated choice sample"),
    LEGACY_OUTCOME_V1("Legacy outcome-v1 row is kept immutable and separate from reviewed-outcome-v2"),
    SYNTHETIC_EXCLUDED("Synthetic row is tagged and excluded from the real evidence approval gate"),
    OUT_OF_SCOPE_MANAGER("Decision actor does not match the requested manager scope");

    private final String description;

    AdaptiveExclusionReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
