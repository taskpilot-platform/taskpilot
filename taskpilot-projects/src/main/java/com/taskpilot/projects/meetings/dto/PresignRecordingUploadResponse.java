package com.taskpilot.projects.meetings.dto;

import lombok.Builder;

import java.time.Instant;

@Builder
public record PresignRecordingUploadResponse(
        String uploadUrl,
        String uploadToken,
        String contentType,
        String fileName,
        long maxFileSizeBytes,
        Instant expiresAt,
        boolean alreadyCompleted,
        Long existingFileId
) {
}
