package com.taskpilot.contracts.project.dto;

public enum AccessRevocationReason {
    MEMBER_LEFT,
    MEMBER_REMOVED,
    ROLE_DEMOTED,
    ACCOUNT_DEACTIVATED,
    ACCOUNT_SUSPENDED,
    PROJECT_DELETED
}
