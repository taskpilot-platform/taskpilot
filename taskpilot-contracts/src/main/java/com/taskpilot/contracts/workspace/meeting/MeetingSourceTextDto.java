package com.taskpilot.contracts.workspace.meeting;

import com.taskpilot.contracts.workspace.chat.ChatMessageSummaryDto;

import java.time.Instant;
import java.util.List;

public record MeetingSourceTextDto(
    Long meetingId,
    String title,
    String transcript,
    String notes,
    String description,
    List<ChatMessageSummaryDto> chatWindowMessages,
    MeetingSourceKind sourceKind,
    Instant sourceUpdatedAt
) {}
