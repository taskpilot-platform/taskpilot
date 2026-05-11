package com.taskpilot.contracts.user.port.out;

public interface NotificationPort {
    void sendSystemNotification(Long targetUserId, String title, String message, String linkAction);
}
