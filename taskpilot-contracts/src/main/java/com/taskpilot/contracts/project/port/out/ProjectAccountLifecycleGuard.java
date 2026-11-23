package com.taskpilot.contracts.project.port.out;

import java.util.List;
import java.util.Set;

/**
 * Projects-owned lifecycle guard participating in the caller's local transaction (no REQUIRES_NEW).
 */
public interface ProjectAccountLifecycleGuard {

    /**
     * Non-locking discovery of project IDs where {@code userId} currently holds MANAGER role.
     */
    List<Long> findCandidateManagerialProjectIds(long userId);

    /**
     * Acquires row locks on {@code projectIds} in ascending ID order and returns all member user IDs
     * associated with those projects so the caller can lock accounts in ascending ID order next.
     */
    Set<Long> lockProjectsAndFindAffectedUserIds(List<Long> projectIds);

    /**
     * Canonical guard entry point. Validates inside the caller's transaction that {@code userId}
     * is not the sole effective ACTIVE manager of any project. When called after
     * {@link #lockProjectsAndFindAffectedUserIds(List)} and account locking, callers should pass
     * the already-locked project ID set via {@link #assertCanSelfDeactivate(long, Set)}.
     */
    void assertCanSelfDeactivate(long userId);

    default void assertCanSelfDeactivate(long userId, Set<Long> lockedProjectIds) {
        assertCanSelfDeactivate(userId);
    }
}
