package com.taskpilot.users.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for refresh and logout endpoints carrying an opaque refresh token.
 */
public record RefreshTokenRequest(
        @NotBlank(message = "Refresh token is required")
        @Pattern(
                regexp = "^[A-Za-z0-9._-]{16,256}$",
                message = "Refresh token format is invalid"
        )
        String refreshToken
) {
}
