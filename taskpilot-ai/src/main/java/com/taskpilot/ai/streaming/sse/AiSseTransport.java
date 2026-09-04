package com.taskpilot.ai.streaming.sse;

import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.service.ChatStreamStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles safe transmission, error shielding, and completion lifecycle for SSE streams.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSseTransport {

    private final ChatStreamStatusService chatStreamStatusService;

    public boolean safeSend(SseEmitter emitter, String event, Object data, MediaType mediaType) {
        try {
            if (mediaType == null) {
                emitter.send(SseEmitter.event().name(event).data(data));
            } else {
                emitter.send(SseEmitter.event().name(event).data(data, mediaType));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("[SSE] safeSend failed for event '{}': {}", event, e.getMessage());
            return false;
        }
    }

    public void safeComplete(SseEmitter emitter, AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception ex) {
                log.debug("[SSE] safeComplete suppressed error: {}", ex.getMessage());
            }
        }
    }

    public void sendTokenToClient(
            SseEmitter emitter,
            String token,
            AtomicBoolean clientDisconnected,
            AtomicBoolean generatingMarked,
            Long sessionId,
            String clientMessageId,
            String modelName) {
        if (generatingMarked.compareAndSet(false, true)) {
            safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
            chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.GENERATING, modelName, null, null);
            safeSend(emitter, "phase", Phase.GENERATING.name(), null);
        }
        if (!clientDisconnected.get()) {
            safeSend(emitter, "token", Map.of("token", token), MediaType.APPLICATION_JSON);
        }
    }

    public boolean isClientAbort(Throwable error) {
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
                if (normalized.contains("aborted") || normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("async request")
                        || normalized.contains("not usable")
                        || normalized.contains("response already committed")
                        || normalized.contains("stream closed")
                        || normalized.contains("already completed")) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    public String normalizeClientMessageId(String clientMessageId) {
        if (clientMessageId == null) {
            return null;
        }
        String trimmed = clientMessageId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
