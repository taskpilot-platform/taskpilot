package com.taskpilot.contracts.workspace.meeting;

import java.util.List;
import java.util.Map;

public record MeetingTaskCreationResultDto(
    List<Map<String, Object>> created,
    List<Map<String, Object>> skipped,
    List<Map<String, Object>> failed
) {}
