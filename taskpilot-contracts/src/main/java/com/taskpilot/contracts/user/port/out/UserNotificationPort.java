package com.taskpilot.contracts.user.port.out;

import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;

public interface UserNotificationPort {

    void createSystemNotification(SystemNotificationCommandDto command);
}
