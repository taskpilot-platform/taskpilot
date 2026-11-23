package com.taskpilot.projects.chat.config;

import com.taskpilot.infrastructure.config.security.CorsOriginProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final CorsOriginProperties corsOriginProperties;
    private final StompOutboundAuthorizationInterceptor stompOutboundAuthorizationInterceptor;
    private final StompWebSocketSessionTracker stompWebSocketSessionTracker;

    @Autowired
    public WebSocketConfig(
            StompAuthChannelInterceptor stompAuthChannelInterceptor,
            CorsOriginProperties corsOriginProperties,
            StompOutboundAuthorizationInterceptor stompOutboundAuthorizationInterceptor,
            StompWebSocketSessionTracker stompWebSocketSessionTracker
    ) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.corsOriginProperties = corsOriginProperties;
        this.stompOutboundAuthorizationInterceptor = stompOutboundAuthorizationInterceptor;
        this.stompWebSocketSessionTracker = stompWebSocketSessionTracker;
    }

    public WebSocketConfig(
            StompAuthChannelInterceptor stompAuthChannelInterceptor,
            CorsOriginProperties corsOriginProperties
    ) {
        this(stompAuthChannelInterceptor, corsOriginProperties, null, null);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] allowedOrigins = corsOriginProperties.getAllowedOriginsArray();
        registry.addEndpoint("/ws/chat")
                .setAllowedOrigins(allowedOrigins)
                .withSockJS();

        registry.addEndpoint("/ws/chat")
                .setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        if (stompOutboundAuthorizationInterceptor != null) {
            registration.interceptors(stompOutboundAuthorizationInterceptor);
        }
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        if (stompWebSocketSessionTracker != null) {
            registry.addDecoratorFactory(stompWebSocketSessionTracker.decoratorFactory());
        }
    }
}
