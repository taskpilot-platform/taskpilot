package com.taskpilot.contracts.assignment.dto;

import java.time.Instant;

public record TaskAssignmentMetrics(
        long projectId, long userId, Instant asOf,
        Performance performance, Workload workload) {

    public enum DataStatus { DEFAULT, MEASURED }

    public record Performance(String version, String rubricVersion, DataStatus status,
            double score, Double observedQualityMean, int eligibleTaskCount, int windowLimit,
            double evidenceWeight, int datedTaskCount, Double onTimeRate, int noDueDateTaskCount,
            String evidenceRevision) {}

    public record Workload(long activeTaskCount, String unit, String scope, String revision) {}
}
