package com.taskpilot.contracts.storage;

/**
 * Durable lifecycle states for storage cleanup jobs.
 */
public enum StorageCleanupStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    DEAD_LETTER
}
