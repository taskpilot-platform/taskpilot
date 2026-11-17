package com.taskpilot.ai.service;

import com.taskpilot.contracts.task.TaskEvaluationAccepted;
import com.taskpilot.contracts.task.TaskEvaluationInvalidated;
import com.taskpilot.contracts.task.TaskEvaluationJournalConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Idempotent downstream projection receiver for P5 task evaluation lifecycle journal events.
 * Tracks processed event IDs without activating P6/P8/P9 learner rollups.
 */
@Slf4j
@Service
public class TaskEvaluationJournalListener implements TaskEvaluationJournalConsumer {

    private static final int MAX_TRACKED_EVENTS = 10_000;

    private final Map<UUID, String> processedEvents = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
                    return size() > MAX_TRACKED_EVENTS;
                }
            });

    @Override
    public void onEvaluationAccepted(TaskEvaluationAccepted event) {
        if (event == null || event.eventId() == null) {
            return;
        }
        if (processedEvents.putIfAbsent(event.eventId(), "ACCEPTED") != null) {
            return;
        }
        log.debug("[AiProjection] Recorded accepted task evaluation eventId={} taskId={} evalId={} status={}",
                event.eventId(), event.taskId(), event.evaluationId(), event.evidenceStatus());
    }

    @Override
    public void onEvaluationInvalidated(TaskEvaluationInvalidated event) {
        if (event == null || event.eventId() == null) {
            return;
        }
        if (processedEvents.putIfAbsent(event.eventId(), "INVALIDATED") != null) {
            return;
        }
        log.debug("[AiProjection] Recorded invalidated task evaluation eventId={} taskId={} evalId={} reason={}",
                event.eventId(), event.taskId(), event.evaluationId(), event.reason());
    }

    public boolean hasProcessed(UUID eventId) {
        return eventId != null && processedEvents.containsKey(eventId);
    }
}
