package com.taskpilot.ai.dto;

/**
 * Metric data status classification for TaskPilot candidate recommendation criteria.
 *
 * Enforces Decision H-011:
 * - MEASURED: Criterion is computed from verified task requirements and recorded member data.
 * - DEFAULT: Criterion source contract is an uncalibrated default prior (e.g. baseline 0.50).
 * - INSUFFICIENT_DATA: Missing task requirements or missing member profile; cannot be scored.
 * - UNVERIFIED: Stored value has no proven provenance, freshness, or project scope.
 */
public enum MetricDataStatus {
    MEASURED,
    DEFAULT,
    INSUFFICIENT_DATA,
    UNVERIFIED
}
