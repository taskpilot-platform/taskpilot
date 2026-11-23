package com.taskpilot.contracts.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only summary of a durable storage cleanup job for observability and testing.
 */
public record StorageCleanupJobSummary(
        long id,
        String dedupeKey,
        StorageProviderKind provider,
        String bucket,
        String objectKey,
        String sourceType,
        String sourceId,
        Long projectId,
        UUID uploadOperationId,
        StorageCleanupStatus status,
        int attempts,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant leaseUntil,
        String lastErrorCode,
        String lastErrorMessage,
        Instant completedAt,
        Instant createdAt
) {
}
