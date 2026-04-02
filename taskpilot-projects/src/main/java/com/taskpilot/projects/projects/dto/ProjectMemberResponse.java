package com.taskpilot.projects.projects.dto;

import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity.MemberRole;

import java.time.Instant;

public record ProjectMemberResponse(
        Long projectId,
        Long userId,
        MemberRole role,
    Instant joinedAt
) {
    public static ProjectMemberResponse fromEntity(ProjectMemberEntity entity) {
        return new ProjectMemberResponse(
        entity.getProjectId(),
                entity.getUserId(),
                entity.getRole(),
        entity.getJoinedAt()
        );
    }
}
