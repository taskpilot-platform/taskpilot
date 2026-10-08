package com.taskpilot.projects.meetings.dto;

import com.taskpilot.projects.common.enums.ProjectMeetingRole;
import lombok.Builder;

import java.time.Instant;

@Builder
public record MeetingParticipantResponse(
        Long id,
        Long meetingId,
        Long userId,
        String name,
        String email,
        String avatarUrl,
        ProjectMeetingRole role,
        Instant joinedAt,
        Instant leftAt,
        boolean isActive
) {
}
