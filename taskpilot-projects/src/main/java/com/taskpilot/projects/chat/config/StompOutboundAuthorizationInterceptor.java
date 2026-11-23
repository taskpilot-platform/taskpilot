package com.taskpilot.projects.chat.config;

import com.taskpilot.contracts.realtime.RealtimeAccessDecision;
import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import com.taskpilot.contracts.realtime.RealtimeAccessPort;
import com.taskpilot.projects.realtime.RealtimeSubscriptionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outbound STOMP channel interceptor authorizing every private {@code MESSAGE} delivery
 * against the recipient's registered subscription and current DB authority.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompOutboundAuthorizationInterceptor implements ChannelInterceptor {

    private static final Pattern PROJECT_CHAT_TOPIC = Pattern.compile("^/topic/projects/(\\d{1,18})/chat$");

    private final RealtimeSubscriptionRegistry subscriptionRegistry;
    private final RealtimeAccessPort realtimeAccessPort;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (message == null) {
            return null;
        }
        SimpMessageType messageType = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        if (messageType != SimpMessageType.MESSAGE) {
            return message;
        }

        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        Matcher matcher = PROJECT_CHAT_TOPIC.matcher(destination != null ? destination : "");
        if (!matcher.matches()) {
            return null;
        }

        long projectId;
        try {
            projectId = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }

        String simpSessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        String subscriptionId = SimpMessageHeaderAccessor.getSubscriptionId(message.getHeaders());
        if (subscriptionRegistry == null || realtimeAccessPort == null
                || simpSessionId == null || subscriptionId == null) {
            return null;
        }

        RealtimeSubscriptionRegistry.RegisteredSubscription subscription =
                subscriptionRegistry.lookupSubscription(simpSessionId, subscriptionId, projectId);
        if (subscription == null) {
            subscriptionRegistry.closeAndRemove(simpSessionId, RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED);
            return emptyClosedSessionHeartbeat(simpSessionId);
        }

        RealtimeAccessDecision decision = realtimeAccessPort.evaluateProjectAccess(projectId, subscription.context());
        if (!decision.allowed()) {
            log.debug("Dropping unauthorized STOMP MESSAGE projectId={} userId={} sessionId={} reason={}",
                    projectId, subscription.context().userId(), simpSessionId, decision.denyReason());
            subscriptionRegistry.closeAndRemove(simpSessionId, decision.denyReason());
            return emptyClosedSessionHeartbeat(simpSessionId);
        }

        return message;
    }

    /**
     * Replaces a dropped private {@code MESSAGE} with an empty {@code HEARTBEAT} marker for the
     * already-closed {@code simpSessionId} so {@code AbstractMessageChannel.send} does not throw
     * {@code MessageDeliveryException} and abort {@code SimpleBrokerMessageHandler}'s fanout to valid subscribers.
     */
    private static Message<byte[]> emptyClosedSessionHeartbeat(String simpSessionId) {
        SimpMessageHeaderAccessor heartbeat = SimpMessageHeaderAccessor.create(SimpMessageType.HEARTBEAT);
        heartbeat.setSessionId(simpSessionId);
        return MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders());
    }
}
