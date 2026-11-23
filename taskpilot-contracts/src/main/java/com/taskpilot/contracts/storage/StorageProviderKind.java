package com.taskpilot.contracts.storage;

/**
 * Supported external object storage providers.
 */
public enum StorageProviderKind {
    S3,
    GOOGLE_DRIVE,
    B2;

    public static StorageProviderKind fromProviderAndBucket(String provider, String bucket) {
        // V43 introduced a default of s3; existing and legacy uploads still carry their Drive bucket.
        if ((provider == null || provider.isBlank() || "s3".equalsIgnoreCase(provider.trim()))
                && fromBucket(bucket) == GOOGLE_DRIVE) {
            return GOOGLE_DRIVE;
        }
        if (provider == null || provider.isBlank()) {
            return fromBucket(bucket);
        }
        return switch (provider.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "b2" -> B2;
            case "gdrive", "googledrive", "google_drive" -> GOOGLE_DRIVE;
            case "s3" -> S3;
            default -> throw new IllegalArgumentException("Unsupported storage provider: " + provider);
        };
    }

    public static StorageProviderKind fromBucket(String bucket) {
        if (bucket != null && "googledrive".equalsIgnoreCase(bucket.trim())) {
            return GOOGLE_DRIVE;
        }
        return S3;
    }
}
