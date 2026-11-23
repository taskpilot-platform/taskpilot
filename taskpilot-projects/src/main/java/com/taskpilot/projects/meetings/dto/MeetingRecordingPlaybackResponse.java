package com.taskpilot.projects.meetings.dto;

import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record MeetingRecordingPlaybackResponse(
        String url,
        String provider,
        boolean requiresAuthHeader,
        String contentType,
        String fileName,
        Long fileSize,
        Instant expiresAt,
        Long recordingFileId,
        String egressId,
        MeetingRecordingStatus recordingStatus,
        boolean backupStorageUsed,
        String recordingError
) {
}
