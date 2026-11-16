package com.taskpilot.infrastructure.storage.range;

import java.util.Optional;

/**
 * Provider-agnostic object read request supporting optional exact byte range.
 */
public record StorageReadRequest(
        String bucket,
        String key,
        Optional<ByteRange> range
) {
    public StorageReadRequest {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        range = range != null ? range : Optional.empty();
    }
}
