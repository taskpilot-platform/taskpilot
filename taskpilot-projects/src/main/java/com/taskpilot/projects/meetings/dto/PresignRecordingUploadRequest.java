package com.taskpilot.projects.meetings.dto;

import jakarta.validation.constraints.NotBlank;

public record PresignRecordingUploadRequest(
        @NotBlank(message = "Content-Type is required")
        String contentType,
        String fileName
) {
}
