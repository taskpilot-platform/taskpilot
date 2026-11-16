package com.taskpilot.contracts.project.port.out;

import com.taskpilot.contracts.user.dto.UserLifecycleRecoveryRequestedEvent;

/**
 * Asynchronous after-commit recovery consumer implemented by projects for admin suspension and reactivation.
 */
public interface ProjectAccountLifecycleRecoveryPort {

    void reconcileUserLifecycle(UserLifecycleRecoveryRequestedEvent event);
}
