package com.taskpilot.users.auth.dto;

public record AuthResponse(
        String token,
        String type,
        Long expiresIn
) {
}