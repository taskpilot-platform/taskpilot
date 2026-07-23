package com.taskpilot.contracts.user.event;

public record ProjectMemberEvent(Long targetUserId, String title, String message, String linkAction) {
}
