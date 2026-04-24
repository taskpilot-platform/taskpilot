package com.taskpilot.users.notifications.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.users.notifications.dto.NotificationResponse;
import com.taskpilot.users.notifications.service.NotificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

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
        return ApiResponse.success(
                HttpStatus.OK.value(),
                "Notifications retrieved successfully",
                notificationService.getMyNotifications(authentication.getName(), pageable));
    }

    @Operation(summary = "Get unread notifications count")
    @GetMapping("/my/unread-count")
    public ApiResponse<Long> getUnreadCount(Authentication authentication) {
        return ApiResponse.success(
                HttpStatus.OK.value(),
                "Unread notifications count retrieved successfully",
                notificationService.getUnreadCount(authentication.getName()));
    }

    @Operation(summary = "Mark one notification as read")
    @PutMapping("/{notificationId}/read")
    public ApiResponse<NotificationResponse> markAsRead(
            @PathVariable Long notificationId,
            Authentication authentication) {
        return ApiResponse.success(
                HttpStatus.OK.value(),
                "Notification marked as read",
                notificationService.markAsRead(notificationId, authentication.getName()));
    }

    @Operation(summary = "Mark all notifications as read")
    @PutMapping("/read-all")
    public ApiResponse<Integer> markAllAsRead(Authentication authentication) {
        return ApiResponse.success(
                HttpStatus.OK.value(),
                "All notifications marked as read",
                notificationService.markAllAsRead(authentication.getName()));
    }
}
