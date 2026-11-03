package com.taskpilot.contracts.workspace.meeting;

import java.time.Instant;

public record MeetingParticipantDto(
    Long userId,
    String name,
    String role,
    Instant joinedAt,
    Instant leftAt
) {}
