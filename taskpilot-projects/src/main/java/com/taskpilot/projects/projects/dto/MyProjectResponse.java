package com.taskpilot.projects.projects.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.taskpilot.projects.common.entity.ProjectEntity.ProjectStatus;
import com.taskpilot.projects.common.entity.ProjectMemberEntity.MemberRole;

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
