package com.taskpilot.users.notifications.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.taskpilot.users.notifications.dto.NotificationResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class NotificationRealtimeService {

    private static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId, long unreadCount) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emittersByUser.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            remove(userId, emitter);
            emitter.complete();
        });
        emitter.onError(error -> remove(userId, emitter));

        safeSend(userId, emitter, "notification.unread-count", unreadCount);
        return emitter;
    }

    public void publishCreated(NotificationResponse notification, long unreadCount) {
        publish(notification.userId(), "notification.created", notification);
        publish(notification.userId(), "notification.unread-count", unreadCount);
    }

    private void publish(Long userId, String eventName, Object payload) {
        List<SseEmitter> emitters = emittersByUser.getOrDefault(userId, new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : emitters) {
            safeSend(userId, emitter, eventName, payload);
        }
    }

    private void safeSend(Long userId, SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Removing notification SSE emitter for userId={}: {}", userId, ex.getMessage());
            remove(userId, emitter);
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByUser.remove(userId);
        }
    }
}
