package com.taskpilot.infrastructure.realtime;

import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared SSE control-envelope and lifecycle helper used by project comment and user notification streams.
 */
public final class RealtimeSseSupport {

    public static final String ACCESS_LOST_EVENT = "realtime.access_lost";
    public static final int DEFAULT_MAX_EMITTERS_PER_USER = 16;

    private RealtimeSseSupport() {
    }

    public static void sendAccessLostAndComplete(
            SseEmitter emitter,
            AtomicBoolean completed,
            RealtimeAccessDenyReason reason,
            String detail
    ) {
        if (emitter == null) {
            return;
        }
        String wireReason = (reason != null ? reason : RealtimeAccessDenyReason.SESSION_REVOKED).toWireCode();
        Map<String, String> payload = detail != null && !detail.isBlank()
                ? Map.of("reason", wireReason, "detail", detail)
                : Map.of("reason", wireReason);
        try {
            emitter.send(SseEmitter.event()
                    .name(ACCESS_LOST_EVENT)
                    .data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ignored) {
            // Stream may already be closed by client or container.
        }
        completeOnce(emitter, completed);
    }

    public static void completeOnce(SseEmitter emitter, AtomicBoolean completed) {
        if (emitter != null && completed != null && completed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // Ignore completion races when container already finalized the emitter.
            }
        }
    }

    public static boolean isClientAbort(Throwable error) {
        if (error == null) {
            return false;
        }
        Throwable current = error;
        while (current != null) {
            String className = current.getClass().getName();
            if ("org.apache.catalina.connector.ClientAbortException".equals(className)
                    || "org.springframework.web.context.request.async.AsyncRequestNotUsableException".equals(className)
                    || "ClientAbortException".equals(className)
                    || "AsyncRequestNotUsableException".equals(className)) {
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
}
