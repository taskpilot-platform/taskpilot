package com.taskpilot.users.admin.dto;

import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;

public record AdminUpdateUserRequest(
        UserRole role,
        UserStatus status,
        Integer currentWorkload
) {
}
