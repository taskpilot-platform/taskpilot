package com.taskpilot.contracts.workspace.meeting;

import java.time.Instant;
import java.util.List;

public record MeetingSummaryDto(
    Long meetingId,
    String title,
    String overview,
    List<String> keyDecisions,
    List<TaskActionItemDto> actionItems,
    List<String> openQuestions,
    MeetingSourceKind sourceUsed,
    Instant generatedAt,
    boolean fromCache
) {}
