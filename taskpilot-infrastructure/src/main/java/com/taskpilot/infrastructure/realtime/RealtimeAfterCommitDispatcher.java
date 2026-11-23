package com.taskpilot.infrastructure.realtime;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Executes realtime side-effects only after the surrounding business transaction commits,
 * preventing phantom broadcasts/notifications on rollback.
 */
public final class RealtimeAfterCommitDispatcher {

    private RealtimeAfterCommitDispatcher() {
    }

    public static void runAfterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
