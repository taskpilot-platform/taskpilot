package com.taskpilot.projects.chat.config;

import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active STOMP {@link WebSocketSession} handles by {@code simpSessionId} so
 * idle or revoked connections can be closed immediately with typed STOMP ERROR + CloseStatus.
 */
@Slf4j
@Component
public class StompWebSocketSessionTracker {

    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;

    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    public WebSocketHandlerDecoratorFactory decoratorFactory() {
        return delegate -> new TrackedWebSocketHandler(delegate, activeSessions);
    }

    public boolean isSessionOpen(String simpSessionId) {
        if (simpSessionId == null) {
            return false;
        }
        WebSocketSession session = activeSessions.get(simpSessionId);
        return session != null && session.isOpen();
    }

    public void closeSession(String simpSessionId, RealtimeAccessDenyReason reason) {
        if (simpSessionId == null || simpSessionId.isBlank()) {
            return;
        }
        WebSocketSession session = activeSessions.remove(simpSessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        RealtimeAccessDenyReason effectiveReason = reason != null ? reason : RealtimeAccessDenyReason.SESSION_REVOKED;
        String wireCode = effectiveReason.toWireCode();
        int closeCode = effectiveReason == RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED ? 4403 : 4401;

        try {
            String errorFrame = "ERROR\nmessage:" + wireCode + "\ncontent-length:0\n\n\0";
            session.sendMessage(new TextMessage(errorFrame));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to send STOMP ERROR before closing sessionId={}: {}", simpSessionId, ex.getMessage());
        }

        try {
            session.close(new CloseStatus(closeCode, wireCode));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to close WebSocket sessionId={}: {}", simpSessionId, ex.getMessage());
        }
    }

    private static final class TrackedWebSocketHandler extends WebSocketHandlerDecorator {

        private final Map<String, WebSocketSession> activeSessions;

        private TrackedWebSocketHandler(WebSocketHandler delegate, Map<String, WebSocketSession> activeSessions) {
            super(delegate);
            this.activeSessions = activeSessions;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                    session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
            activeSessions.put(session.getId(), safeSession);
            super.afterConnectionEstablished(session);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
            activeSessions.remove(session.getId());
            super.afterConnectionClosed(session, closeStatus);
        }
    }
}
