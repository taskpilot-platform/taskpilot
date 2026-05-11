package com.taskpilot.projects.projects.dto;

import com.taskpilot.projects.common.entity.ProjectMemberEntity.MemberRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(
    @NotNull(message = "Role is required")
    MemberRole role
) {}
