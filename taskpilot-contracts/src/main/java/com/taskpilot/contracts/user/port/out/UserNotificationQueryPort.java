package com.taskpilot.contracts.user.port.out;

import java.util.List;

import com.taskpilot.contracts.user.dto.NotificationSummaryDto;

public interface UserNotificationQueryPort {
    List<NotificationSummaryDto> getMyNotifications(Long userId, boolean unreadOnly, int limit);

    long getUnreadNotificationCount(Long userId);

    NotificationSummaryDto markNotificationRead(Long notificationId, Long userId);

    int markAllNotificationsRead(Long userId);
}
