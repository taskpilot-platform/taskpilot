package com.taskpilot.projects.chat.config;

import com.taskpilot.infrastructure.observability.TaskPilotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketSessionMetricsListenerTest {

    @Test
    void tracksConnectAndDisconnectEventsInActiveSessionGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskPilotMetrics metrics = new TaskPilotMetrics(registry, ObservationRegistry.NOOP);
        WebSocketSessionMetricsListener listener = new WebSocketSessionMetricsListener(metrics);

        listener.onSessionConnected(new SessionConnectedEvent(this, stompMessage(StompCommand.CONNECTED, "ws-1")));
        listener.onSessionConnected(new SessionConnectedEvent(this, stompMessage(StompCommand.CONNECTED, "ws-2")));
        assertThat(registry.find("taskpilot.websocket.sessions.active").gauge().value()).isEqualTo(2.0);

        listener.onSessionDisconnected(new SessionDisconnectEvent(this, stompMessage(StompCommand.DISCONNECT, "ws-1"), "ws-1", CloseStatus.NORMAL));
        assertThat(registry.find("taskpilot.websocket.sessions.active").gauge().value()).isEqualTo(1.0);

        listener.onSessionDisconnected(new SessionDisconnectEvent(this, stompMessage(StompCommand.DISCONNECT, "ws-2"), "ws-2", CloseStatus.NORMAL));
        assertThat(registry.find("taskpilot.websocket.sessions.active").gauge().value()).isEqualTo(0.0);
    }

    private static Message<byte[]> stompMessage(StompCommand command, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
