package com.taskpilot.projects.chat.config;

import com.taskpilot.infrastructure.observability.TaskPilotMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Tracks active WebSocket STOMP chat sessions via an idempotent session-id set gauge
 * ({@code taskpilot_websocket_sessions_active}).
 */
@Component
@RequiredArgsConstructor
public class WebSocketSessionMetricsListener {

    private final TaskPilotMetrics metrics;

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        metrics.onWebSocketConnected(accessor.getSessionId());
    }

    @EventListener
    public void onSessionDisconnected(SessionDisconnectEvent event) {
        metrics.onWebSocketDisconnected(event.getSessionId());
    }
}
