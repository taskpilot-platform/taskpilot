package com.taskpilot.infrastructure.storage.range;

import java.io.InputStream;
import java.util.Optional;

/**
 * Provider-agnostic streamed object read result.
 */
public record StorageReadResult(
        InputStream body,
        long totalBytes,
        long responseBytes,
        String contentType,
        Optional<ByteRange> servedRange,
        Optional<String> etag
) {
    public StorageReadResult {
        servedRange = servedRange != null ? servedRange : Optional.empty();
        etag = etag != null ? etag : Optional.empty();
    }
}
