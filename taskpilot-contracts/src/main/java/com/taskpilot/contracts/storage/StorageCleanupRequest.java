package com.taskpilot.contracts.storage;

import java.util.Objects;
import java.util.UUID;

/**
 * Request to enqueue durable after-commit object deletion.
 */
public record StorageCleanupRequest(
        StorageObjectLocator locator,
        String sourceType,
        String sourceId,
        Long projectId,
        UUID uploadOperationId
) {
    public StorageCleanupRequest {
        Objects.requireNonNull(locator, "locator must not be null");
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        sourceType = sourceType.trim();
        sourceId = sourceId != null ? sourceId.trim() : null;
    }
}
