package com.taskpilot.infrastructure.storage.range;

import java.time.Instant;
import java.util.Optional;

/**
 * Lightweight object metadata used for range validation and If-Range preconditions.
 */
public record StorageObjectStat(
        long totalBytes,
        String contentType,
        Optional<String> etag,
        Optional<Instant> lastModified
) {
    public StorageObjectStat {
        etag = etag != null ? etag : Optional.empty();
        lastModified = lastModified != null ? lastModified : Optional.empty();
    }
}
