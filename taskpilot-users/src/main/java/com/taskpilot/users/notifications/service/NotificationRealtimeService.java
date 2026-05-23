package com.taskpilot.users.notifications.service;

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

import com.taskpilot.users.notifications.dto.NotificationResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class NotificationRealtimeService {

    private static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 25_000L;

    private final Map<Long, CopyOnWriteArrayList<EmitterRegistration>> emittersByUser =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "notification-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    @PostConstruct
    void startHeartbeatLoop() {
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_INTERVAL_MILLIS,
                HEARTBEAT_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopHeartbeatLoop() {
        heartbeatExecutor.shutdownNow();
    }

    public SseEmitter subscribe(Long userId, long unreadCount) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        EmitterRegistration registration =
                new EmitterRegistration("notif-" + UUID.randomUUID(), emitter);
        CopyOnWriteArrayList<EmitterRegistration> userEmitters =
                emittersByUser.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>());
        userEmitters.add(registration);
        log.debug("Notification SSE opened userId={} emitterId={} active={}", userId,
                registration.id(), userEmitters.size());

        emitter.onCompletion(() -> {
            log.debug("Notification SSE completed userId={} emitterId={}", userId,
                    registration.id());
            remove(userId, registration, "completion", null, false);
        });
        emitter.onTimeout(() -> {
            log.warn("Notification SSE timed out userId={} emitterId={}", userId,
                    registration.id());
            remove(userId, registration, "timeout", null, true);
        });
        emitter.onError(error -> {
            if (isClientAbort(error)) {
                log.debug("Notification SSE client abort userId={} emitterId={} reason={}", userId,
                        registration.id(), error.getMessage());
            } else {
                log.warn("Notification SSE error userId={} emitterId={} reason={}", userId,
                        registration.id(), error.getMessage());
            }
            remove(userId, registration, "error", error, false);
        });

        safeSendJson(userId, registration, "notification.unread-count", unreadCount, "initial");
        return emitter;
    }

    public void publishCreated(NotificationResponse notification, long unreadCount) {
        publish(notification.userId(), "notification.created", notification);
        publish(notification.userId(), "notification.unread-count", unreadCount);
    }

    private void publish(Long userId, String eventName, Object payload) {
        List<EmitterRegistration> emitters =
                emittersByUser.getOrDefault(userId, new CopyOnWriteArrayList<>());
        for (EmitterRegistration emitter : emitters) {
            safeSendJson(userId, emitter, eventName, payload, "publish");
        }
    }

    private void sendHeartbeats() {
        try {
            emittersByUser.forEach((userId, emitters) -> {
                for (EmitterRegistration emitter : emitters) {
                    safeSendHeartbeat(userId, emitter);
                }
            });
        } catch (Exception ex) {
            log.warn("Notification SSE heartbeat loop failed: {}", ex.getMessage());
        }
    }

    private boolean safeSendJson(Long userId, EmitterRegistration emitter, String eventName,
            Object payload, String source) {
        try {
            emitter.emitter().send(
                    SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug(
                    "Notification SSE send failed userId={} emitterId={} source={} event={} reason={}",
                    userId, emitter.id(), source, eventName, ex.getMessage());
            remove(userId, emitter, "send-failed", ex, false);
            return false;
        }
    }

    private boolean safeSendHeartbeat(Long userId, EmitterRegistration emitter) {
        try {
            emitter.emitter().send(SseEmitter.event().comment("keep-alive"));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Notification SSE heartbeat failed userId={} emitterId={} reason={}", userId,
                    emitter.id(), ex.getMessage());
            remove(userId, emitter, "heartbeat-failed", ex, false);
            return false;
        }
    }

    private void remove(Long userId, EmitterRegistration emitter, String reason, Throwable error,
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

        List<EmitterRegistration> emitters = emittersByUser.get(userId);
        if (emitters == null) {
            return;
        }

        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByUser.remove(userId);
        }

        String message = error != null ? error.getMessage() : "-";
        log.debug(
                "Notification SSE removed userId={} emitterId={} reason={} remaining={} detail={}",
                userId, emitter.id(), reason, emitters.size(), message);

        if (emitters.isEmpty()) {
            log.debug("Notification SSE stream list empty for userId={}", userId);
        }
    }

    private boolean isClientAbort(Throwable error) {
        if (error == null) {
            return false;
        }

        Throwable current = error;
        while (current != null) {
            String className = current.getClass().getName();
            if ("ClientAbortException".equals(className)
                    || "AsyncRequestNotUsableException".equals(className)) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("aborted") || normalized.contains("broken pipe")
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

    private record EmitterRegistration(String id, SseEmitter emitter, AtomicBoolean removed,
            AtomicBoolean completed) {

        private EmitterRegistration(String id, SseEmitter emitter) {
            this(id, emitter, new AtomicBoolean(false), new AtomicBoolean(false));
        }
    }
}
