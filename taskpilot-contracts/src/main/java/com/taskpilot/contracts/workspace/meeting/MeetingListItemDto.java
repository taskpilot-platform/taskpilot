package com.taskpilot.contracts.workspace.meeting;

import java.time.Instant;

public record MeetingListItemDto(
    Long id,
    String title,
    String status,
    Instant scheduledStartTime,
    Instant scheduledEndTime,
    Instant startedAt,
    Instant endedAt,
    Long hostId,
    String hostName,
    boolean hasRecording,
    boolean hasNotes,
    boolean hasTranscript,
    boolean hasAiSummary
) {}
