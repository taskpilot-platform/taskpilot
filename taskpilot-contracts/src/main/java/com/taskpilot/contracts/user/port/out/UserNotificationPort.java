package com.taskpilot.contracts.user.port.out;

import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;

public interface UserNotificationPort {

    void createNotification(SystemNotificationCommandDto command);

    default void createSystemNotification(SystemNotificationCommandDto command) {
        createNotification(command);
    }
}
