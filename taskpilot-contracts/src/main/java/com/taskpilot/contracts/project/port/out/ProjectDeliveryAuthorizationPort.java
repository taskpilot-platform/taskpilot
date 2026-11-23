package com.taskpilot.contracts.project.port.out;

/**
 * Projects-owned delivery guard checked before creating or dispatching project-scoped notifications.
 */
public interface ProjectDeliveryAuthorizationPort {

    boolean canDeliverProjectNotification(long projectId, Long taskId, long targetUserId);

    /** Pre-persistence check participating in the caller's business transaction and row locks. */
    boolean canCreateProjectNotificationInCurrentTransaction(long projectId, Long taskId, long targetUserId);
}
