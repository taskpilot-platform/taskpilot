package com.taskpilot.infrastructure.storage.range;

/**
 * Outcome of attempting to delete an object from an external storage provider.
 */
public enum StorageDeleteOutcome {
    DELETED,
    NOT_FOUND,
    RETRYABLE_FAILURE,
    AUTH_FAILURE
}
