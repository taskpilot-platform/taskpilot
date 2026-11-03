package com.taskpilot.contracts.workspace.file;

public record ProjectFileDetailDto(
    ProjectFileSummaryDto file,
    Long linkedMeetingId,
    String linkedMeetingTitle,
    FileAccessLinkDto link
) {}
