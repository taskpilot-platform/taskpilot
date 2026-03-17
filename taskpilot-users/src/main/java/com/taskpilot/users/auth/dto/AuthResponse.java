package com.taskpilot.users.auth.dto;

public record AuthResponse(
        String token,
        String refreshToken,
        String type,
        Long expiresIn
) {
}