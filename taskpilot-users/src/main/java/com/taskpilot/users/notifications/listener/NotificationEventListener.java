package com.taskpilot.users.notifications.listener;

import com.taskpilot.contracts.project.port.out.ProjectDeliveryAuthorizationPort;
import com.taskpilot.contracts.user.dto.NotificationTypeDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.event.ProjectMemberEvent;
import com.taskpilot.contracts.user.event.TaskAssignedEvent;
import com.taskpilot.contracts.user.event.TaskCommentedEvent;
import com.taskpilot.users.notifications.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final ObjectProvider<ProjectDeliveryAuthorizationPort> projectDeliveryAuthProvider;

    @Autowired
    public NotificationEventListener(
            NotificationService notificationService,
            ObjectProvider<ProjectDeliveryAuthorizationPort> projectDeliveryAuthProvider
    ) {
        this.notificationService = notificationService;
        this.projectDeliveryAuthProvider = projectDeliveryAuthProvider;
    }

    public NotificationEventListener(NotificationService notificationService) {
        this(notificationService, null);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTaskAssigned(TaskAssignedEvent event) {
        if (event == null || event.targetUserId() == null || event.projectId() == null) {
            return;
        }
        log.debug("Processing TaskAssignedEvent asynchronously for userId={}", event.targetUserId());
        notificationService.createNotification(new SystemNotificationCommandDto(
                event.targetUserId(),
                "Task Assigned",
                "You have been assigned to task: " + event.taskTitle(),
                event.linkAction(),
                NotificationTypeDto.ASSIGNED, event.projectId(), event.taskId()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTaskCommented(TaskCommentedEvent event) {
        if (event == null || event.targetUserId() == null || event.projectId() == null) {
            return;
        }
        if (event.projectId() != null) {
            ProjectDeliveryAuthorizationPort port = projectDeliveryAuthProvider != null
                    ? projectDeliveryAuthProvider.getIfAvailable()
                    : null;
            if (port == null || !port.canDeliverProjectNotification(
                    event.projectId(), event.taskId(), event.targetUserId())) {
                log.debug("Suppressed TaskCommentedEvent delivery for ineligible userId={} projectId={} taskId={}",
                        event.targetUserId(), event.projectId(), event.taskId());
                return;
            }
        }
        log.debug("Processing TaskCommentedEvent asynchronously for userId={} projectId={}",
                event.targetUserId(), event.projectId());
        notificationService.createNotification(new SystemNotificationCommandDto(
                event.targetUserId(),
                event.title(),
                event.message(),
                event.linkAction(),
                event.type(),
                event.projectId(),
                event.taskId()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProjectMember(ProjectMemberEvent event) {
        if (event == null || event.targetUserId() == null || event.projectId() == null) {
            return;
        }
        log.debug("Processing ProjectMemberEvent asynchronously for userId={}", event.targetUserId());
        notificationService.createNotification(new SystemNotificationCommandDto(
                event.targetUserId(),
                event.title(),
                event.message(),
                event.linkAction(),
                NotificationTypeDto.SYSTEM, event.projectId(), null));
    }
}
