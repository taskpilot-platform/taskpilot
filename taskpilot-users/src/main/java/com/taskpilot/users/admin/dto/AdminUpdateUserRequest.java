package com.taskpilot.users.admin.dto;

import com.taskpilot.users.entity.UserEntity.UserRole;
import com.taskpilot.users.entity.UserEntity.UserStatus;

public record AdminUpdateUserRequest(
        UserRole role,
        UserStatus status,
        Integer currentWorkload
) {
}
