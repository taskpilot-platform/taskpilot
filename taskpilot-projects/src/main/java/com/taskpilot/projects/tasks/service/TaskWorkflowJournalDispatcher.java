package com.taskpilot.projects.tasks.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taskpilot.contracts.task.TaskEvaluationAccepted;
import com.taskpilot.contracts.task.TaskEvaluationInvalidated;
import com.taskpilot.contracts.task.TaskEvaluationJournalConsumer;
import com.taskpilot.projects.common.entity.TaskWorkflowJournalEntity;
import com.taskpilot.projects.common.repository.TaskWorkflowJournalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Durable post-commit dispatcher for task evaluation/workflow journal records.
 * Consumer failures mark entries RETRY_PENDING without ever rolling back committed
 * task acceptance or evaluation state.
 */
@Slf4j
@Service
public class TaskWorkflowJournalDispatcher {

    public static final String EVENT_EVALUATION_ACCEPTED = "TASK_EVALUATION_ACCEPTED";
    public static final String EVENT_EVALUATION_INVALIDATED = "TASK_EVALUATION_INVALIDATED";
    private static final int MAX_ATTEMPTS = 10;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private final TaskWorkflowJournalRepository journalRepository;
    private final List<TaskEvaluationJournalConsumer> consumers;
    private final TransactionTemplate requiresNewTemplate;
    private final Clock clock;

    @Autowired
    public TaskWorkflowJournalDispatcher(
            TaskWorkflowJournalRepository journalRepository,
            @Autowired(required = false) List<TaskEvaluationJournalConsumer> consumers,
            PlatformTransactionManager transactionManager,
            Optional<Clock> clock) {
        this(journalRepository, consumers, transactionManager,
                clock != null ? clock.orElse(Clock.systemUTC()) : Clock.systemUTC());
    }

    public TaskWorkflowJournalDispatcher(
            TaskWorkflowJournalRepository journalRepository,
            List<TaskEvaluationJournalConsumer> consumers,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.journalRepository = journalRepository;
        this.consumers = consumers != null ? List.copyOf(consumers) : List.of();
        if (transactionManager != null) {
            TransactionTemplate tpl = new TransactionTemplate(transactionManager);
            tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTemplate = tpl;
        } else {
            this.requiresNewTemplate = null;
        }
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public void scheduleAfterCommit(List<Long> journalIds) {
        if (journalIds == null || journalIds.isEmpty()) {
            return;
        }
        List<Long> idsCopy = List.copyOf(journalIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchBatchSafely(idsCopy);
                }
            });
        } else {
            dispatchBatchSafely(idsCopy);
        }
    }

    public void dispatchBatchSafely(List<Long> journalIds) {
        for (Long id : journalIds) {
            try {
                if (requiresNewTemplate != null) {
                    requiresNewTemplate.execute(status -> {
                        dispatchSingleLocked(id);
                        return null;
                    });
                } else {
                    dispatchSingleLocked(id);
                }
            } catch (Exception ex) {
                log.warn("[TaskWorkflowJournal] Dispatch attempt failed for journalId={}: {}", id, ex.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void replayDueEntries() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Long> dueIds = journalRepository.findDueJournalIds(now);
        if (!dueIds.isEmpty()) {
            dispatchBatchSafely(dueIds);
        }
    }

    public int replayPendingForTask(long taskId) {
        List<Long> pendingIds = journalRepository.findPendingIdsByTaskId(taskId);
        int succeeded = 0;
        for (Long id : pendingIds) {
            boolean ok;
            if (requiresNewTemplate != null) {
                ok = Boolean.TRUE.equals(requiresNewTemplate.execute(status -> dispatchSingleLocked(id)));
            } else {
                ok = dispatchSingleLocked(id);
            }
            if (ok) {
                succeeded++;
            }
        }
        return succeeded;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean dispatchSingleLocked(long journalId) {
        TaskWorkflowJournalEntity entry = journalRepository.findByIdForUpdate(journalId).orElse(null);
        if (entry == null || "SUCCEEDED".equals(entry.getStatus())) {
            return true;
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            deliverToConsumers(entry);
            entry.setAttempts(entry.getAttempts() + 1);
            entry.setStatus("SUCCEEDED");
            entry.setProcessedAt(now);
            entry.setLastError(null);
            journalRepository.saveAndFlush(entry);
            return true;
        } catch (Exception ex) {
            int nextAttempts = entry.getAttempts() + 1;
            entry.setAttempts(nextAttempts);
            entry.setStatus(nextAttempts >= MAX_ATTEMPTS ? "FAILED" : "RETRY_PENDING");
            long delaySeconds = Math.min(300L, 5L * nextAttempts);
            entry.setNextAttemptAt(now.plusSeconds(delaySeconds));
            entry.setLastError(truncateError(ex.getMessage()));
            journalRepository.saveAndFlush(entry);
            log.warn("[TaskWorkflowJournal] Downstream consumer failed for eventId={} (attempt={}): {}",
                    entry.getEventId(), nextAttempts, ex.getMessage());
            return false;
        }
    }

    private void deliverToConsumers(TaskWorkflowJournalEntity entry) throws Exception {
        if (consumers.isEmpty()) {
            return;
        }
        String type = entry.getEventType();
        if (EVENT_EVALUATION_ACCEPTED.equals(type)) {
            TaskEvaluationAccepted event = MAPPER.readValue(entry.getPayloadJson(), TaskEvaluationAccepted.class);
            for (TaskEvaluationJournalConsumer consumer : consumers) {
                consumer.onEvaluationAccepted(event);
            }
        } else if (EVENT_EVALUATION_INVALIDATED.equals(type)) {
            TaskEvaluationInvalidated event = MAPPER.readValue(entry.getPayloadJson(), TaskEvaluationInvalidated.class);
            for (TaskEvaluationJournalConsumer consumer : consumers) {
                consumer.onEvaluationInvalidated(event);
            }
        }
    }

    private static String truncateError(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown consumer error";
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
