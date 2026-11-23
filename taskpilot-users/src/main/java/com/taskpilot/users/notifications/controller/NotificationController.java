package com.taskpilot.users.notifications.controller;

import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.notifications.dto.NotificationResponse;
import com.taskpilot.users.notifications.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "08. Notifications", description = "APIs for user notifications")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "Get my notifications")
    @GetMapping("/my")
    public ApiResponse<Page<NotificationResponse>> getMyNotifications(
            Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Notifications retrieved successfully",
                notificationService.getMyNotifications(CurrentActorResolver.userId(authentication), pageable));
    }

    @Operation(summary = "Get unread notifications count")
    @GetMapping("/my/unread-count")
    public ApiResponse<Long> getUnreadCount(Authentication authentication) {
        return ApiResponse.ok("Unread notifications count retrieved successfully",
                notificationService.getUnreadCount(CurrentActorResolver.userId(authentication)));
    }

    @Operation(summary = "Stream my notifications")
    @GetMapping(value = "/my/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMyNotifications(
            Authentication authentication,
            HttpServletResponse response) throws IOException {
        try {
            RealtimeAccessContext context = CurrentActorResolver.extractRealtimeContext(authentication)
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authenticated realtime session required"));
            return notificationService.streamMyNotifications(context);
        } catch (BusinessException ex) {
            if (!response.isCommitted()) {
                int status = ex.getStatus();
                response.setStatus(status);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + safeSseErrorMessage(status) + "\"}");
                response.getWriter().flush();
            }
            return null;
        }
    }

    private static String safeSseErrorMessage(int status) {
        return switch (status) {
            case 401 -> "Unauthorized";
            case 403 -> "Access denied";
            case 404 -> "Resource not found";
            default -> "Request rejected";
        };
    }

    @Operation(summary = "Get notification detail by ID")
    @GetMapping("/{notificationId}")
    public ApiResponse<NotificationResponse> getNotificationById(
            @PathVariable Long notificationId,
            Authentication authentication) {
        return ApiResponse.ok("Notification retrieved successfully",
                notificationService.getNotificationById(notificationId, CurrentActorResolver.userId(authentication)));
    }

    @Operation(summary = "Mark one notification as read")
    @PutMapping("/{notificationId}/read")
    public ApiResponse<NotificationResponse> markAsRead(
            @PathVariable Long notificationId,
            Authentication authentication) {
        return ApiResponse.ok("Notification marked as read",
                notificationService.markAsRead(notificationId, CurrentActorResolver.userId(authentication)));
    }

    @Operation(summary = "Mark all notifications as read")
    @PutMapping("/read-all")
    public ApiResponse<Integer> markAllAsRead(Authentication authentication) {
        return ApiResponse.ok("All notifications marked as read",
                notificationService.markAllAsRead(CurrentActorResolver.userId(authentication)));
    }
}
