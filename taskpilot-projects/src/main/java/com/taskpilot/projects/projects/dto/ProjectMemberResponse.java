package com.taskpilot.projects.projects.dto;

import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity.MemberRole;

import java.time.Instant;

public record ProjectMemberResponse(
        Long projectId,
        Long userId,
        String fullName,
        String email,
        String avatarUrl,
        MemberRole role,
        Instant joinedAt
) {
    public static ProjectMemberResponse fromEntity(ProjectMemberEntity entity) {
        return new ProjectMemberResponse(
                entity.getProjectId(),
                entity.getUserId(),
                null,
                null,
                null,
                entity.getRole(),
                entity.getJoinedAt()
        );
    }

    public static ProjectMemberResponse fromEntityWithProfile(
            ProjectMemberEntity entity, String fullName, String email, String avatarUrl) {
        return new ProjectMemberResponse(
                entity.getProjectId(),
                entity.getUserId(),
                fullName,
                email,
                avatarUrl,
                entity.getRole(),
                entity.getJoinedAt()
        );
    }
}
