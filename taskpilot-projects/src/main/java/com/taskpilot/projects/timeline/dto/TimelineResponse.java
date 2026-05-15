package com.taskpilot.projects.timeline.dto;

import java.util.List;

public record TimelineResponse(
        TimelineProjectDto project,
        List<TimelineSprintDto> sprints,
        List<TimelineTaskDto> unscheduledTasks) {
}
