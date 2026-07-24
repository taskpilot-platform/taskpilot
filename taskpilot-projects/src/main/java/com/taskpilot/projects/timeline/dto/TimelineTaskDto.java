package com.taskpilot.projects.timeline.dto;

import java.time.Instant;

import com.taskpilot.projects.common.enums.PriorityLevel;
import com.taskpilot.projects.common.enums.TaskStatus;

public record TimelineTaskDto(
        Long id,
        String title,
        TaskStatus status,
        PriorityLevel priority,
        Instant startDate,
        Instant dueDate) {
}
