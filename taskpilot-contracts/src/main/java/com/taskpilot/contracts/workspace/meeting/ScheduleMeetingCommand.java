package com.taskpilot.contracts.workspace.meeting;

import java.time.Instant;

public record ScheduleMeetingCommand(
    String title,
    String description,
    Instant start,
    Instant end,
    boolean recordingEnabled
) {}
