package com.taskpilot.contracts.storage;

import java.time.Duration;
import java.util.Objects;

/**
 * Command to reserve a durable upload operation before calling an external provider.
 */
public record StorageUploadReservationCommand(
        long ownerUserId,
        long projectId,
        StorageTargetDomain targetDomain,
        StorageProviderKind provider,
        String bucket,
        String plannedObjectKey,
        String originalFilename,
        String contentType,
        long expectedSize,
        Duration leaseDuration
) {
    public StorageUploadReservationCommand(
            long ownerUserId,
            long projectId,
            StorageTargetDomain targetDomain,
            StorageProviderKind provider,
            String bucket,
            String plannedObjectKey,
            String originalFilename,
            String contentType,
            long expectedSize
    ) {
        this(ownerUserId, projectId, targetDomain, provider, bucket, plannedObjectKey,
                originalFilename, contentType, expectedSize, null);
    }

    public StorageUploadReservationCommand {
        Objects.requireNonNull(targetDomain, "targetDomain must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("originalFilename must not be blank");
        }
        if (expectedSize < 0) {
            throw new IllegalArgumentException("expectedSize must be non-negative");
        }
        if (leaseDuration != null && (leaseDuration.isZero() || leaseDuration.isNegative())) {
            throw new IllegalArgumentException("leaseDuration must be positive when provided");
        }
        bucket = bucket.trim();
        plannedObjectKey = plannedObjectKey != null && !plannedObjectKey.isBlank() ? plannedObjectKey.trim() : null;
        originalFilename = originalFilename.trim();
    }
}
