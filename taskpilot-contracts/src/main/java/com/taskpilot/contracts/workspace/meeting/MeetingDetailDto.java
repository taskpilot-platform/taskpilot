package com.taskpilot.contracts.workspace.meeting;

import java.util.List;

public record MeetingDetailDto(
    MeetingListItemDto meeting,
    String description,
    List<MeetingParticipantDto> participants,
    Long recordingFileId,
    String recordingFileName,
    String recordingAppRoute,
    String aiSummaryJson
) {}
