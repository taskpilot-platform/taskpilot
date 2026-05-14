package com.taskpilot.projects.sprints.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Size;

public record UpdateSprintRequest(
        @Size(max = 255, message = "Sprint name must not exceed 255 characters")
        String name,

        @Size(max = 2000, message = "Goal must not exceed 2000 characters")
        String goal,

        LocalDate startDate,

        LocalDate endDate) {
}
