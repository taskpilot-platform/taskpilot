package com.taskpilot.contracts.user.dto;

/**
 * Categorized triggers that advance {@code users.token_version} and invalidate all active user sessions.
 */
public enum SecurityChangeReason {
    PASSWORD_CHANGED,
    PASSWORD_RESET,
    ROLE_CHANGED,
    USER_DEACTIVATED,
    USER_SUSPENDED,
    USER_DELETED,
    SESSION_COMPROMISED
}
