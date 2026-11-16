package com.taskpilot.users.auth.service;

import com.taskpilot.contracts.project.port.out.ProjectAccountLifecycleRecoveryPort;
import com.taskpilot.contracts.user.dto.UserLifecycleRecoveryRequestedEvent;
import com.taskpilot.users.common.entity.UserLifecycleRecoveryOutboxEntity;
import com.taskpilot.users.common.repository.UserLifecycleRecoveryOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Durable users-side outbox for administrative suspension and reactivation project recovery.
 * Outbox rows are written atomically inside the users suspension/reactivation transaction
 * without acquiring project locks; delivery runs strictly after commit with bounded retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserLifecycleRecoveryOutboxDispatcher {

    private static final int MAX_ATTEMPTS = 20;
    private static final int BATCH_SIZE = 25;

    private final UserLifecycleRecoveryOutboxRepository outboxRepository;
    private final ObjectProvider<ProjectAccountLifecycleRecoveryPort> recoveryPortProvider;
    private final PlatformTransactionManager transactionManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public UserLifecycleRecoveryOutboxEntity enqueueInCurrentTx(long userId, long tokenVersion, String lifecycle) {
        Instant now = Instant.now();
        UserLifecycleRecoveryOutboxEntity row = UserLifecycleRecoveryOutboxEntity.builder()
                .eventId(UUID.randomUUID())
                .userId(userId)
                .tokenVersion(tokenVersion)
                .lifecycle(lifecycle)
                .occurredAt(now)
                .status(UserLifecycleRecoveryOutboxEntity.STATUS_PENDING)
                .attemptCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build();
        UserLifecycleRecoveryOutboxEntity saved = outboxRepository.saveAndFlush(row);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            UUID eventId = saved.getEventId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        dispatchSingle(eventId);
                    } catch (Exception ex) {
                        log.warn("Immediate after-commit recovery dispatch failed for eventId={}, will retry via outbox: {}",
                                eventId, ex.getMessage());
                    }
                }
            });
        }
        return saved;
    }

    @Scheduled(fixedDelayString = "${application.security.lifecycle-recovery.poll-interval-ms:15000}")
    public void scheduledDrain() {
        dispatchPendingBatch();
    }

    public int dispatchPendingBatch() {
        if (recoveryPortProvider.getIfAvailable() == null) {
            return 0;
        }
        List<UUID> dueIds = outboxRepository.findDuePendingIds(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        int reconciled = 0;
        for (UUID eventId : dueIds) {
            if (dispatchSingle(eventId)) {
                reconciled++;
            }
        }
        return reconciled;
    }

    public boolean dispatchSingle(UUID eventId) {
        ProjectAccountLifecycleRecoveryPort recoveryPort = recoveryPortProvider.getIfAvailable();
        if (recoveryPort == null || eventId == null) {
            return false;
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        UserLifecycleRecoveryRequestedEvent event = tx.execute(status -> {
            UserLifecycleRecoveryOutboxEntity row = outboxRepository.findByEventIdForUpdate(eventId).orElse(null);
            if (row == null || !UserLifecycleRecoveryOutboxEntity.STATUS_PENDING.equals(row.getStatus())) {
                return null;
            }
            Instant now = Instant.now();
            if (row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now)) {
                return null;
            }
            row.setLeaseUntil(now.plusSeconds(30));
            outboxRepository.saveAndFlush(row);
            return new UserLifecycleRecoveryRequestedEvent(
                    row.getEventId(),
                    row.getUserId(),
                    row.getTokenVersion(),
                    row.getLifecycle(),
                    row.getOccurredAt()
            );
        });

        if (event == null) {
            return false;
        }

        try {
            recoveryPort.reconcileUserLifecycle(event);
            tx.executeWithoutResult(status -> outboxRepository.findByEventIdForUpdate(eventId).ifPresent(row -> {
                row.setStatus(UserLifecycleRecoveryOutboxEntity.STATUS_RECONCILED);
                row.setReconciledAt(Instant.now());
                row.setLeaseUntil(null);
                row.setLastError(null);
                outboxRepository.saveAndFlush(row);
            }));
            return true;
        } catch (Exception ex) {
            log.warn("Outbox lifecycle recovery failed for eventId={}, userId={}: {}",
                    eventId, event.userId(), ex.getMessage());
            tx.executeWithoutResult(status -> outboxRepository.findByEventIdForUpdate(eventId).ifPresent(row -> {
                int attempts = (row.getAttemptCount() != null ? row.getAttemptCount() : 0) + 1;
                row.setAttemptCount(attempts);
                row.setLeaseUntil(null);
                String msg = ex.getMessage();
                row.setLastError(msg != null && msg.length() > 480 ? msg.substring(0, 480) : msg);
                if (attempts >= MAX_ATTEMPTS) {
                    row.setStatus(UserLifecycleRecoveryOutboxEntity.STATUS_FAILED_PERMANENT);
                } else {
                    long backoffSec = Math.min(300L, 2L * (1L << Math.min(attempts, 7)));
                    row.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(backoffSec)));
                }
                outboxRepository.saveAndFlush(row);
            }));
            return false;
        }
    }
}
