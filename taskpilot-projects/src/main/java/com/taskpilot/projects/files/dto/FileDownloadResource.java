package com.taskpilot.projects.files.dto;

import java.io.InputStream;

public record FileDownloadResource(
        String fileName,
        String contentType,
        long fileSize,
        InputStream inputStream
) {
}
