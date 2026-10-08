package com.taskpilot.projects.meetings.dto;

import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record ProjectMeetingResponse(
        Long id,
        Long projectId,
        Long hostId,
        String hostName,
        String hostAvatarUrl,
        String title,
        String description,
        String roomName,
        ProjectMeetingStatus status,
        Boolean recordingEnabled,
        Long recordingFileId,
        Instant startedAt,
        Instant endedAt,
        Long durationSeconds,
        long activeParticipantsCount,
        long totalParticipantsCount,
        boolean isHost
) {
}
