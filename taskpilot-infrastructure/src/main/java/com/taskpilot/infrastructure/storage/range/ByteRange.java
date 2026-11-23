package com.taskpilot.infrastructure.storage.range;

/**
 * Inclusive byte range [startInclusive, endInclusive].
 */
public record ByteRange(long startInclusive, long endInclusive) {

    public ByteRange {
        if (startInclusive < 0) {
            throw new IllegalArgumentException("startInclusive must be >= 0");
        }
        if (endInclusive < startInclusive) {
            throw new IllegalArgumentException("endInclusive must be >= startInclusive");
        }
    }

    public long length() {
        return (endInclusive - startInclusive) + 1L;
    }

    public String toHttpRangeHeader() {
        return "bytes=" + startInclusive + "-" + endInclusive;
    }

    public String toContentRangeHeader(long totalBytes) {
        return "bytes " + startInclusive + "-" + endInclusive + "/" + totalBytes;
    }
}
