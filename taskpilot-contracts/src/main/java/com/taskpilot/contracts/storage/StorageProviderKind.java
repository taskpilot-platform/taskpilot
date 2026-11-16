package com.taskpilot.contracts.storage;

/**
 * Supported external object storage providers.
 */
public enum StorageProviderKind {
    S3,
    GOOGLE_DRIVE;

    public static StorageProviderKind fromBucket(String bucket) {
        if (bucket != null && "googledrive".equalsIgnoreCase(bucket.trim())) {
            return GOOGLE_DRIVE;
        }
        return S3;
    }
}
