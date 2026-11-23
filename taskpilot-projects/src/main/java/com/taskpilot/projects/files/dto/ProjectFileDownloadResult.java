package com.taskpilot.projects.files.dto;

import com.taskpilot.infrastructure.storage.range.ByteRange;

import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

/**
 * Download response model carrying HTTP status semantics (200, 206, 416),
 * authoritative byte coordinates, validators, and the provider stream.
 */
public record ProjectFileDownloadResult(
        Status status,
        String fileName,
        String contentType,
        long totalBytes,
        long responseBytes,
        Optional<ByteRange> servedRange,
        Optional<String> etag,
        Optional<Instant> lastModified,
        InputStream inputStream
) {
    public enum Status {
        FULL_200,
        PARTIAL_206,
        RANGE_NOT_SATISFIABLE_416
    }

    public ProjectFileDownloadResult {
        servedRange = servedRange != null ? servedRange : Optional.empty();
        etag = etag != null ? etag : Optional.empty();
        lastModified = lastModified != null ? lastModified : Optional.empty();
    }
}
