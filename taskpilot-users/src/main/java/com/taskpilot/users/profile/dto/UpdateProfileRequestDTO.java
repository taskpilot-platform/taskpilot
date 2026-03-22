package com.taskpilot.users.profile.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequestDTO(
        @NotBlank(message = "Full name cannot be blank") 
        String fullName,
        
        String avatarUrl
) {
}
