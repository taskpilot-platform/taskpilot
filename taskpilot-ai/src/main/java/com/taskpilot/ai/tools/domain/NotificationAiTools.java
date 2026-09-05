package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.user.port.out.UserNotificationQueryPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.taskpilot.ai.tools.support.AiToolSupport.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationAiTools {

    private final UserNotificationQueryPort userNotificationQueryPort;
    private final PendingAiActionService pendingAiActionService;

    @Tool("List notifications for the current user. Set unreadOnly=true to get unread notifications only.")
    public String getMyNotifications(
            @P("Return only unread notifications when true") Boolean unreadOnly,
            @P("Maximum number of notifications to return, between 1 and 50") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        boolean onlyUnread = Boolean.TRUE.equals(unreadOnly);
        log.info("[AiTool] getMyNotifications called for user {} unreadOnly={} limit={}",
                userId, onlyUnread, safeLimit);
        try {
            return PATCH_OBJECT_MAPPER.writeValueAsString(
                userNotificationQueryPort.getMyNotifications(userId, onlyUnread, safeLimit)
            );
        } catch (Exception e) {
            log.error("[AiTool] Failed to serialize notifications", e);
            return "[]";
        }
    }


    @Tool("Get the count of unread notifications for the current user.")
    public Object getUnreadNotificationCount() {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] getUnreadNotificationCount called for user {}", userId);
        return Map.of("unreadCount", userNotificationQueryPort.getUnreadNotificationCount(userId));
    }


    @Tool("Mark a specific notification as read by its ID. Requires confirmation.")
    public Object markNotificationRead(@P("The ID of the notification to mark as read") Long notificationId) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] markNotificationRead called for notification {}", notificationId);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "markNotificationRead",
                "Mark notification " + notificationId + " as read",
                args("notificationId", notificationId),
                args("notificationId", notificationId),
                () -> userNotificationQueryPort.markNotificationRead(notificationId, userId));
    }


    @Tool("Mark all notifications for the current user as read. Requires confirmation.")
    public Object markAllNotificationsRead() {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] markAllNotificationsRead called for user {}", userId);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "markAllNotificationsRead",
                "Mark all notifications as read",
                args(),
                null,
                () -> Map.of("updatedCount", userNotificationQueryPort.markAllNotificationsRead(userId)));
    }


}
