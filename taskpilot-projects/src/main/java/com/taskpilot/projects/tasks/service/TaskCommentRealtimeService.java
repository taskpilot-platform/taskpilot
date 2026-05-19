package com.taskpilot.projects.tasks.service;

import java.io.IOException;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

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
    private static final long HEARTBEAT_INTERVAL_MILLIS = 25_000L;

    private final Map<Long, CopyOnWriteArrayList<EmitterRegistration>> emittersByTask = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "comment-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void startHeartbeatLoop() {
        heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeats,
                HEARTBEAT_INTERVAL_MILLIS,
                HEARTBEAT_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopHeartbeatLoop() {
        heartbeatExecutor.shutdownNow();
    }

    public SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        EmitterRegistration registration = new EmitterRegistration("comment-" + UUID.randomUUID(), emitter);
        CopyOnWriteArrayList<EmitterRegistration> taskEmitters = emittersByTask
                .computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>());
        taskEmitters.add(registration);
        log.debug("Comment SSE opened taskId={} emitterId={} active={}",
                taskId,
                registration.id(),
                taskEmitters.size());

        emitter.onCompletion(() -> {
            log.debug("Comment SSE completed taskId={} emitterId={}", taskId, registration.id());
            remove(taskId, registration, "completion", null, false);
        });
        emitter.onTimeout(() -> {
            log.warn("Comment SSE timed out taskId={} emitterId={}", taskId, registration.id());
            remove(taskId, registration, "timeout", null, true);
        });
        emitter.onError(error -> {
            if (isClientAbort(error)) {
                log.debug("Comment SSE client abort taskId={} emitterId={} reason={}",
                        taskId,
                        registration.id(),
                        error.getMessage());
            } else {
                log.warn("Comment SSE error taskId={} emitterId={} reason={}",
                        taskId,
                        registration.id(),
                        error.getMessage());
            }
            remove(taskId, registration, "error", error, false);
        });

        safeSendJson(taskId, registration, "comment.connected", Map.of("taskId", taskId), "initial");
        return emitter;
    }

    public void publishCreated(TaskCommentDto comment) {
        publish(comment.taskId(), "comment.created", comment);
    }

    public void publishUpdated(TaskCommentDto comment) {
        publish(comment.taskId(), "comment.updated", comment);
    }

    public void publishDeleted(TaskCommentDto comment) {
        publish(comment.taskId(), "comment.deleted",
                new TaskCommentDeletedEvent(comment.taskId(), comment.id(), comment.parentCommentId(), true));
    }

    private void publish(Long taskId, String eventName, Object payload) {
        List<EmitterRegistration> emitters = emittersByTask.getOrDefault(taskId, new CopyOnWriteArrayList<>());
        for (EmitterRegistration emitter : emitters) {
            safeSendJson(taskId, emitter, eventName, payload, "publish");
        }
    }

    private void sendHeartbeats() {
        try {
            emittersByTask.forEach((taskId, emitters) -> {
                for (EmitterRegistration emitter : emitters) {
                    safeSendHeartbeat(taskId, emitter);
                }
            });
        } catch (Exception ex) {
            log.warn("Comment SSE heartbeat loop failed: {}", ex.getMessage());
        }
    }

    private boolean safeSendJson(Long taskId, EmitterRegistration emitter, String eventName, Object payload,
            String source) {
        try {
            emitter.emitter().send(SseEmitter.event()
                    .name(eventName)
                    .data(payload, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Comment SSE send failed taskId={} emitterId={} source={} event={} reason={}",
                    taskId,
                    emitter.id(),
                    source,
                    eventName,
                    ex.getMessage());
            remove(taskId, emitter, "send-failed", ex, false);
            return false;
        }
    }

    private boolean safeSendHeartbeat(Long taskId, EmitterRegistration emitter) {
        try {
            emitter.emitter().send(SseEmitter.event().comment("keep-alive"));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Comment SSE heartbeat failed taskId={} emitterId={} reason={}",
                    taskId,
                    emitter.id(),
                    ex.getMessage());
            remove(taskId, emitter, "heartbeat-failed", ex, false);
            return false;
        }
    }

    private void remove(Long taskId, EmitterRegistration emitter, String reason, Throwable error,
            boolean completeEmitter) {
        if (!emitter.removed().compareAndSet(false, true)) {
            return;
        }

        if (completeEmitter && emitter.completed().compareAndSet(false, true)) {
            try {
                emitter.emitter().complete();
            } catch (IllegalStateException ignore) {
                // Ignore completion races when container already finalized the emitter.
            }
        }

        List<EmitterRegistration> emitters = emittersByTask.get(taskId);
        if (emitters == null) {
            return;
        }

        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByTask.remove(taskId);
        }

        String message = error != null ? error.getMessage() : "-";
        log.debug("Comment SSE removed taskId={} emitterId={} reason={} remaining={} detail={}",
                taskId,
                emitter.id(),
                reason,
                emitters.size(),
                message);

        if (emitters.isEmpty()) {
            log.debug("Comment SSE stream list empty for taskId={}", taskId);
        }
    }

    private boolean isClientAbort(Throwable error) {
        if (error == null) {
            return false;
        }

        Throwable current = error;
        while (current != null) {
            String className = current.getClass().getName();
            if ("org.apache.catalina.connector.ClientAbortException".equals(className)
                    || "org.springframework.web.context.request.async.AsyncRequestNotUsableException".equals(className)) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("aborted")
                        || normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("stream closed")
                        || normalized.contains("not usable")) {
                    return true;
                }
            }

            current = current.getCause();
        }
        return false;
    }

    private record EmitterRegistration(
            String id,
            SseEmitter emitter,
            AtomicBoolean removed,
            AtomicBoolean completed) {

        private EmitterRegistration(String id, SseEmitter emitter) {
            this(id, emitter, new AtomicBoolean(false), new AtomicBoolean(false));
        }
    }
}
