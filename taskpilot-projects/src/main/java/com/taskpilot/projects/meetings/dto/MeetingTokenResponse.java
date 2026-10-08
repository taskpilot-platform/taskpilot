package com.taskpilot.projects.meetings.dto;

import lombok.Builder;

@Builder
public record MeetingTokenResponse(
        String token,
        String livekitUrl,
        String roomName,
        String identity,
        String participantName,
        boolean isHost,
        ProjectMeetingResponse meeting
) {
}
