package com.taskpilot.projects.meetings.dto;

import jakarta.validation.constraints.NotBlank;

public record CompleteRecordingUploadRequest(
        @NotBlank(message = "uploadToken is required")
        String uploadToken,
        Long durationSeconds
) {
}
