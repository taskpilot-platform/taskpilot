package com.taskpilot.infrastructure.storage.range;

import java.util.Objects;

/**
 * Structured outcome of an external storage delete operation.
 */
public record StorageDeleteResult(
        StorageDeleteOutcome outcome,
        String errorCode,
        String safeMessage
) {
    public StorageDeleteResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    public boolean isSuccess() {
        return outcome == StorageDeleteOutcome.DELETED || outcome == StorageDeleteOutcome.NOT_FOUND;
    }

    public static StorageDeleteResult deleted() {
        return new StorageDeleteResult(StorageDeleteOutcome.DELETED, null, null);
    }

    public static StorageDeleteResult notFound() {
        return new StorageDeleteResult(StorageDeleteOutcome.NOT_FOUND, "NOT_FOUND", "Object already absent");
    }

    public static StorageDeleteResult authFailure(String errorCode, String safeMessage) {
        return new StorageDeleteResult(StorageDeleteOutcome.AUTH_FAILURE, errorCode, safeMessage);
    }

    public static StorageDeleteResult retryableFailure(String errorCode, String safeMessage) {
        return new StorageDeleteResult(StorageDeleteOutcome.RETRYABLE_FAILURE, errorCode, safeMessage);
    }
}
