package com.taskpilot.contracts.user.dto;

/**
 * Categorized triggers that revoke an individual auth session ({@code sid}).
 */
public enum SessionRevocationReason {
    LOGOUT,
    REPLAY_DETECTED,
    REPLACED_BY_LOGIN,
    SECURITY_VERSION_INCREMENT
}
