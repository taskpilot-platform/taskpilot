package com.taskpilot.projects.projects.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProjectDeletionAuditDto(
        Long projectId,
        String projectName,
        Long deletedByUserId,
        UUID requestId,
        Map<String, Integer> deletedCounts,
        int enqueuedCleanupJobCount,
        Instant deletedAt
) {
}
