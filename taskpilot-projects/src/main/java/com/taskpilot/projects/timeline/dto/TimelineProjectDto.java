package com.taskpilot.projects.timeline.dto;

import java.time.LocalDate;

public record TimelineProjectDto(
        Long id,
        String name,
        LocalDate startDate,
        LocalDate endDate) {
}
