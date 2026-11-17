package com.taskpilot.users.notifications.service;

import com.taskpilot.contracts.project.port.out.ProjectDeliveryAuthorizationPort;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.port.out.RealtimeSessionAuthorityPort;
import com.taskpilot.infrastructure.notification.OneSignalService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Retains delivery scope across the queue; the provider adapter performs no further queuing. */
@Service
public class NotificationPushDispatcher {
    private final OneSignalService provider;
    private final RealtimeSessionAuthorityPort accounts;
    private final ObjectProvider<ProjectDeliveryAuthorizationPort> projects;

    public NotificationPushDispatcher(OneSignalService provider, RealtimeSessionAuthorityPort accounts,
                                      ObjectProvider<ProjectDeliveryAuthorizationPort> projects) {
        this.provider = provider;
        this.accounts = accounts;
        this.projects = projects;
    }

    @Async
    public void dispatch(SystemNotificationCommandDto command) {
        if (command == null || command.targetUserId() == null || command.targetUserId() <= 0) {
            return;
        }
        boolean eligible;
        if (command.projectId() != null) {
            ProjectDeliveryAuthorizationPort guard = projects.getIfAvailable();
            eligible = guard != null && guard.canDeliverProjectNotification(
                    command.projectId(), command.taskId(), command.targetUserId());
        } else {
            eligible = accounts.isAccountActiveInNewTx(command.targetUserId());
        }
        if (eligible) {
            provider.sendNotificationToUser(String.valueOf(command.targetUserId()), command.title(),
                    command.message(), command.linkAction());
        }
    }
}
