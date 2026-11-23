package com.taskpilot.projects.meetings.service;

public record EgressSnapshot(
        String egressId,
        String roomName,
        String status,
        String error,
        boolean backupStorageUsed,
        String filepath,
        long fileSize,
        long durationNanos
) {
    public boolean isTerminal() {
        return "EGRESS_COMPLETE".equals(status)
                || "EGRESS_FAILED".equals(status)
                || "EGRESS_ABORTED".equals(status)
                || "EGRESS_LIMIT_REACHED".equals(status);
    }
}
