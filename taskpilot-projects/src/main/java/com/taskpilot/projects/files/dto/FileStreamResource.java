package com.taskpilot.projects.files.dto;

import java.io.IOException;
import java.io.InputStream;

public record FileStreamResource(
        String fileName,
        String contentType,
        long totalSize,
        long rangeStart,
        long rangeEnd,
        boolean partial,
        StreamOpener streamOpener
) {
    @FunctionalInterface
    public interface StreamOpener {
        InputStream open() throws IOException;
    }

    public long contentLength() {
        return totalSize <= 0 ? 0L : (rangeEnd - rangeStart + 1);
    }
}
