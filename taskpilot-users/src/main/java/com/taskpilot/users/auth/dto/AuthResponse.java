package com.taskpilot.users.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(
        String token,
        String refreshToken,
        String type,
        Long expiresIn,
        String outcome,
        String restoreTicket,
        Long restoreExpiresIn,
        String reauthTicket,
        Long reauthExpiresIn,
        String email,
        String provider,
        String message
) {
    public AuthResponse(String token, String refreshToken, String type, Long expiresIn) {
        this(token, refreshToken, type, expiresIn, "AUTHENTICATED", null, null, null, null, null, null, null);
    }

    public static AuthResponse restoreRequired(String email, String provider, String restoreTicket, long expiresInSeconds) {
        return new AuthResponse(null, null, null, null, "RESTORE_REQUIRED",
                restoreTicket, expiresInSeconds, null, null, email, provider,
                "Account is self-deactivated. Confirm restoration to continue.");
    }

    public static AuthResponse emailVerificationRequired(String email, String provider, String message) {
        return new AuthResponse(null, null, null, null, "EMAIL_VERIFICATION_REQUIRED",
                null, null, null, null, email, provider, message);
    }

    public static AuthResponse oauthEmailCollision(String email, String provider, String message) {
        return new AuthResponse(null, null, null, null, "OAUTH_EMAIL_COLLISION",
                null, null, null, null, email, provider, message);
    }

    public static AuthResponse linkCompleted(String provider) {
        return new AuthResponse(null, null, null, null, "LINK_COMPLETED",
                null, null, null, null, null, provider, "Provider linked successfully.");
    }

    public static AuthResponse reauthCompleted(String provider, String reauthTicket, long expiresInSeconds) {
        return new AuthResponse(null, null, null, null, "REAUTH_COMPLETED",
                null, null, reauthTicket, expiresInSeconds, null, provider, "Re-authentication verified.");
    }
}
