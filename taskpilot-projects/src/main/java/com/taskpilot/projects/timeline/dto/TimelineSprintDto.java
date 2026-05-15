package com.taskpilot.projects.timeline.dto;

import java.time.LocalDate;
import java.util.List;

import com.taskpilot.projects.common.entity.SprintEntity.SprintStatus;

public record TimelineSprintDto(
        Long id,
        String name,
        SprintStatus status,
        LocalDate startDate,
        LocalDate endDate,
        List<TimelineTaskDto> tasks) {
}
