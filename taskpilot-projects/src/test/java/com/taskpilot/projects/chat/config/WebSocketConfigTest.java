package com.taskpilot.projects.chat.config;

import com.taskpilot.infrastructure.config.security.CorsOriginProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {

    @Test
    void registerStompEndpointsUsesExplicitAllowlistAndNeverWildcardPatterns() {
        CorsOriginProperties corsProps = new CorsOriginProperties(
                "", "https://app.taskpilot.test,http://localhost:5173");
        WebSocketConfig config = new WebSocketConfig(mock(StompAuthChannelInterceptor.class), corsProps);

        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration sockJsEndpoint = mock(StompWebSocketEndpointRegistration.class);
        StompWebSocketEndpointRegistration rawEndpoint = mock(StompWebSocketEndpointRegistration.class);
        SockJsServiceRegistration sockJs = mock(SockJsServiceRegistration.class);

        when(registry.addEndpoint("/ws/chat")).thenReturn(sockJsEndpoint, rawEndpoint);
        when(sockJsEndpoint.setAllowedOrigins(any(String[].class))).thenReturn(sockJsEndpoint);
        when(sockJsEndpoint.withSockJS()).thenReturn(sockJs);
        when(rawEndpoint.setAllowedOrigins(any(String[].class))).thenReturn(rawEndpoint);

        config.registerStompEndpoints(registry);

        ArgumentCaptor<String[]> sockOrigins = ArgumentCaptor.forClass(String[].class);
        verify(sockJsEndpoint).setAllowedOrigins(sockOrigins.capture());
        assertThat(sockOrigins.getValue()).containsExactly("https://app.taskpilot.test", "http://localhost:5173");
        verify(sockJsEndpoint, never()).setAllowedOriginPatterns(any());

        ArgumentCaptor<String[]> rawOrigins = ArgumentCaptor.forClass(String[].class);
        verify(rawEndpoint).setAllowedOrigins(rawOrigins.capture());
        assertThat(rawOrigins.getValue()).containsExactly("https://app.taskpilot.test", "http://localhost:5173");
        verify(rawEndpoint, never()).setAllowedOriginPatterns(any());
        verify(registry, times(2)).addEndpoint("/ws/chat");
    }
}
