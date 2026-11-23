package com.taskpilot.projects.meetings.dto;

import jakarta.validation.constraints.NotBlank;

public record PresignRecordingUploadRequest(
        @NotBlank(message = "Content-Type is required")
        String contentType,
        String fileName,
        String clientRecordingId
) {
    public PresignRecordingUploadRequest(String contentType, String fileName) {
        this(contentType, fileName, null);
    }
}
