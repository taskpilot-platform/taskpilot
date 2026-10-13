package com.taskpilot.ai.dto;

import java.time.Instant;

/**
 * Phase 2C measured workload contract (H-022).
 * Captures non-negative active assigned task count, measurement unit, data status,
 * measurement timestamp, and evaluation scope.
 */
public record WorkloadMeasurement(
        int value,
        String unit,
        MetricDataStatus status,
        Instant measuredAt,
        String scope
) {
    public static final String UNIT_ACTIVE_TASK_COUNT = "ACTIVE_TASK_COUNT";
    public static final String SCOPE_PROJECT = "PROJECT";

    public static WorkloadMeasurement measured(int value, Instant measuredAt) {
        return new WorkloadMeasurement(Math.max(0, value), UNIT_ACTIVE_TASK_COUNT, MetricDataStatus.MEASURED, measuredAt, SCOPE_PROJECT);
    }

    public static WorkloadMeasurement unverified(int fallbackValue) {
        return new WorkloadMeasurement(fallbackValue, null, MetricDataStatus.UNVERIFIED, null, null);
    }
}
