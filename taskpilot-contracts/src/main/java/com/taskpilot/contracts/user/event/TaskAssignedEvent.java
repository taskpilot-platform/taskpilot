package com.taskpilot.contracts.user.event;

public record TaskAssignedEvent(Long targetUserId, Long taskId, String taskTitle, String linkAction) {
}
