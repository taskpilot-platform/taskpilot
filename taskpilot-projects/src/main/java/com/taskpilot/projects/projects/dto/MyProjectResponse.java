package com.taskpilot.projects.projects.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectStatus;

public record MyProjectResponse(
        Long id,
        String name,
        String description,
        ProjectStatus status,
        MemberRole myRole,
        LocalDate startDate,
        LocalDate endDate,
        Instant joinedAt
) {
}
