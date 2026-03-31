package com.taskpilot.users.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.taskpilot.users.entity.UserEntity.UserRole;

public record AdminCreateUserRequest(
        @NotBlank(message = "Email cannot be blank")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Full name cannot be blank")
        String fullName,

        @NotNull(message = "Role cannot be null")
        UserRole role
) {
}
