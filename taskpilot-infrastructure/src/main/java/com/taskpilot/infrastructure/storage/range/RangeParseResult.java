package com.taskpilot.infrastructure.storage.range;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of parsing an HTTP Range header against a known object byte length.
 */
public record RangeParseResult(
        Status status,
        Optional<ByteRange> range,
        long totalBytes
) {
    public enum Status {
        NONE,
        SATISFIABLE,
        UNSATISFIABLE
    }

    public RangeParseResult {
        Objects.requireNonNull(status, "status must not be null");
        range = range != null ? range : Optional.empty();
    }

    public static RangeParseResult none(long totalBytes) {
        return new RangeParseResult(Status.NONE, Optional.empty(), totalBytes);
    }

    public static RangeParseResult satisfiable(ByteRange range, long totalBytes) {
        return new RangeParseResult(Status.SATISFIABLE, Optional.of(range), totalBytes);
    }

    public static RangeParseResult unsatisfiable(long totalBytes) {
        return new RangeParseResult(Status.UNSATISFIABLE, Optional.empty(), totalBytes);
    }
}
