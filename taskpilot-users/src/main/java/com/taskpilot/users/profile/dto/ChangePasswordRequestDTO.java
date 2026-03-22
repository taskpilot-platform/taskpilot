package com.taskpilot.users.profile.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequestDTO(
        @NotBlank(message = "Old password cannot be blank") 
        String oldPassword,

        @NotBlank(message = "New password cannot be blank") 
        String newPassword
) {
}
