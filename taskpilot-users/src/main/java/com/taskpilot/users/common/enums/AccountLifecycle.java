package com.taskpilot.users.common.enums;

/**
 * Authoritative security lifecycle state stored in {@code users.account_state}.
 * Only {@link #ACTIVE} accounts may authenticate or maintain valid sessions.
 */
public enum AccountLifecycle {
    ACTIVE,
    SELF_DEACTIVATED,
    ADMIN_SUSPENDED,
    PENDING_EMAIL,
    LEGACY_DISABLED
}
