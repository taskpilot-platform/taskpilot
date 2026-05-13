package com.taskpilot.projects.tasks.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.taskpilot.projects.tasks.dto.TaskCommentDeletedEvent;
import com.taskpilot.projects.tasks.dto.TaskCommentDto;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TaskCommentRealtimeService {

    private static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emittersByTask = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emittersByTask.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> {
            remove(taskId, emitter);
            emitter.complete();
        });
        emitter.onError(error -> remove(taskId, emitter));

        safeSend(taskId, emitter, "comment.connected", Map.of("taskId", taskId));
        return emitter;
    }

    public void publishCreated(TaskCommentDto comment) {
        publish(comment.taskId(), "comment.created", comment);
    }

    public void publishUpdated(TaskCommentDto comment) {
        publish(comment.taskId(), "comment.updated", comment);
    }

    public void publishDeleted(Long taskId, Long commentId) {
        publish(taskId, "comment.deleted", new TaskCommentDeletedEvent(taskId, commentId));
    }

    private void publish(Long taskId, String eventName, Object payload) {
        List<SseEmitter> emitters = emittersByTask.getOrDefault(taskId, new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : emitters) {
            safeSend(taskId, emitter, eventName, payload);
        }
    }

    private void safeSend(Long taskId, SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Removing comment SSE emitter for taskId={}: {}", taskId, ex.getMessage());
            remove(taskId, emitter);
        }
    }

    private void remove(Long taskId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByTask.get(taskId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByTask.remove(taskId);
        }
    }
}
