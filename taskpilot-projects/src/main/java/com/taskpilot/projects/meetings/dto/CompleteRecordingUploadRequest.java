package com.taskpilot.projects.meetings.dto;

import com.taskpilot.projects.common.entity.MeetingRecordingEntity;
import jakarta.validation.constraints.NotBlank;

public record CompleteRecordingUploadRequest(
        @NotBlank(message = "uploadToken is required")
        String uploadToken,
        Long durationSeconds,
        MeetingRecordingEntity.Origin origin,
        MeetingRecordingEntity.Coverage coverage,
        String clientRecordingId
) {
    public CompleteRecordingUploadRequest(String uploadToken, Long durationSeconds) {
        this(uploadToken, durationSeconds, null, null, null);
    }

    public CompleteRecordingUploadRequest(
            String uploadToken,
            Long durationSeconds,
            MeetingRecordingEntity.Origin origin,
            MeetingRecordingEntity.Coverage coverage) {
        this(uploadToken, durationSeconds, origin, coverage, null);
    }
}
