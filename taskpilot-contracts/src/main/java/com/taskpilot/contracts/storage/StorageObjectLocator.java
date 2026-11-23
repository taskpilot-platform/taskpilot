package com.taskpilot.contracts.storage;

import java.util.Objects;

/**
 * Immutable provider-scoped object locator.
 */
public record StorageObjectLocator(
        StorageProviderKind provider,
        String bucket,
        String objectKey
) {
    public StorageObjectLocator {
        Objects.requireNonNull(provider, "provider must not be null");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        bucket = bucket.trim();
        objectKey = objectKey.trim();
    }

    public static StorageObjectLocator of(String bucket, String objectKey) {
        StorageProviderKind kind = StorageProviderKind.fromBucket(bucket);
        String normalizedBucket = (bucket == null || bucket.isBlank()) ? "documents" : bucket.trim();
        return new StorageObjectLocator(kind, normalizedBucket, objectKey);
    }

    public static StorageObjectLocator of(String provider, String bucket, String objectKey) {
        StorageProviderKind kind = StorageProviderKind.fromProviderAndBucket(provider, bucket);
        String normalizedBucket = (bucket == null || bucket.isBlank()) ? "documents" : bucket.trim();
        return new StorageObjectLocator(kind, normalizedBucket, objectKey);
    }

    public String dedupeKey() {
        return provider.name() + ":" + bucket + ":" + objectKey;
    }
}
