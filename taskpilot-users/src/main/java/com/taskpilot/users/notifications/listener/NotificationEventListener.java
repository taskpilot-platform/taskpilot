package com.taskpilot.users.notifications.listener;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.taskpilot.contracts.user.dto.NotificationTypeDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.event.ProjectMemberEvent;
import com.taskpilot.contracts.user.event.TaskAssignedEvent;
import com.taskpilot.contracts.user.event.TaskCommentedEvent;
import com.taskpilot.users.notifications.service.NotificationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTaskAssigned(TaskAssignedEvent event) {
        log.debug("Processing TaskAssignedEvent asynchronously for userId={}", event.targetUserId());
        notificationService.createNotification(new SystemNotificationCommandDto(
                event.targetUserId(),
                "Task Assigned",
                "You have been assigned to task: " + event.taskTitle(),
                event.linkAction(),
                NotificationTypeDto.ASSIGNED));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTaskCommented(TaskCommentedEvent event) {
        log.debug("Processing TaskCommentedEvent asynchronously for userId={}", event.targetUserId());
        notificationService.createNotification(new SystemNotificationCommandDto(
                event.targetUserId(),
                event.title(),
                event.message(),
                event.linkAction(),
                event.type()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProjectMember(ProjectMemberEvent event) {
        log.debug("Processing ProjectMemberEvent asynchronously for userId={}", event.targetUserId());
        notificationService.createNotification(new SystemNotificationCommandDto(
                event.targetUserId(),
                event.title(),
                event.message(),
                event.linkAction(),
                NotificationTypeDto.SYSTEM));
    }
}
