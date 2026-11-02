package com.taskpilot.projects.meetings.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateMeetingRecordingRequest(
        @NotNull(message = "recordingFileId is required") Long recordingFileId
) {
}
