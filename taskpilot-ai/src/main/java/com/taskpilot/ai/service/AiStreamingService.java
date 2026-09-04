package com.taskpilot.ai.service;

import com.taskpilot.ai.context.ChatHistoryCompactor;
import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.entity.ChatMessageEntity.SenderType;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.prompt.SystemPromptBuilder;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.ai.streaming.engine.StreamingChatEngine;
import com.taskpilot.ai.streaming.sse.AiSseTransport;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public Spring Service Facade for AI Chat Streaming.
 * Coordinates session validation, message logging, dynamic routing, and delegates
 * streaming DAG execution to {@link StreamingChatEngine}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiStreamingService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SmartRoutingService routingService;
    private final ChatStreamStatusService chatStreamStatusService;
    private final SessionChatMemoryService sessionChatMemoryService;
    private final StreamingChatEngine streamingChatEngine;
    private final SystemPromptBuilder promptBuilder;
    private final ChatHistoryCompactor compactor;
    private final AiSseTransport sseTransport;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    public SseEmitter streamChat(Long sessionId, Long userId, String userInput, String clientMessageId) {
        ChatSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new SecurityException("Session not found or access denied"));

        SseEmitter emitter = new SseEmitter(300_000L);
        log.info("[SSE] AI chat stream opened for session {}", sessionId);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        long startTime = System.currentTimeMillis();

        String effectiveClientMessageId = normalizeClientMessageId(clientMessageId);
        if (effectiveClientMessageId == null) {
            effectiveClientMessageId = UUID.randomUUID().toString();
        }

        chatStreamStatusService.upsertQueued(sessionId, userId, effectiveClientMessageId);

        Optional<ChatMessageEntity> existing = messageRepository
                .findFirstBySessionIdAndSenderAndClientMessageId(sessionId, SenderType.USER, effectiveClientMessageId);
        if (existing.isPresent()) {
            log.info("[AiChat] Duplicate stream request ignored for session {} clientMessageId={}",
                    sessionId, effectiveClientMessageId);
            sseTransport.safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
            sseTransport.safeSend(emitter, "done", "", null);
            sseTransport.safeComplete(emitter, emitterCompleted);
            return emitter;
        }

        messageRepository.save(ChatMessageEntity.builder()
                .sessionId(sessionId)
                .sender(SenderType.USER)
                .clientMessageId(effectiveClientMessageId)
                .content(userInput)
                .build());

        String systemPrompt = promptBuilder.buildSystemPrompt(userId);
        List<ChatMessage> history = sessionChatMemoryService.appendUserMessage(sessionId, systemPrompt, userInput);
        List<ChatMessage> requestHistory = compactor.compactHistoryForRequest(
                promptBuilder.withSystemPrompt(history, systemPrompt),
                "initial");

        chatStreamStatusService.updatePhase(sessionId, effectiveClientMessageId, Phase.ROUTING, null, null, null);
        sseTransport.safeSend(emitter, "phase", Phase.ROUTING.name(), null);

        String contextText = requestHistory.stream().map(m -> {
            if (m instanceof UserMessage um) return um.singleText();
            if (m instanceof AiMessage am) return am.text() != null ? am.text() : "";
            if (m instanceof SystemMessage sm) return sm.text();
            return "";
        }).reduce("", (a, b) -> a + "\n" + b);

        String routingInput = latestUserMessageText(requestHistory, userInput);
        SmartRoutingService.RoutingDecision decision = routingService.route(routingInput, contextText);
        StreamingChatModel selectedModel = decision.model();
        String modelName = decision.modelName();
        boolean requiresAHP = decision.requiresAHP();
        boolean requiresTools = decision.requiresTools();

        chatStreamStatusService.updatePhase(sessionId, effectiveClientMessageId, Phase.THINKING, modelName, null, null);

        String finalClientMessageId = effectiveClientMessageId;
        executor.submit(() -> {
            try {
                sseTransport.safeSend(emitter, "model", modelName, null);
                sseTransport.safeSend(emitter, "phase", Phase.THINKING.name(), null);
                streamingChatEngine.doStream(
                        emitter, emitterCompleted, session, sessionId, userId, userInput,
                        requestHistory, systemPrompt, selectedModel, modelName, startTime,
                        false, finalClientMessageId, requiresAHP, requiresTools, 0);
            } catch (Exception e) {
                log.error("[SSE] Exception in stream thread for session {}: {}", sessionId, e.getMessage(), e);
                chatStreamStatusService.updatePhase(sessionId, finalClientMessageId, Phase.FAILED, modelName, null, e.getMessage());
                sseTransport.safeSend(emitter, "phase", Phase.FAILED.name(), null);
                sseTransport.safeSend(emitter, "error", Map.of("error", e.getMessage(), "type", "generation_failed"), MediaType.APPLICATION_JSON);
                sseTransport.safeComplete(emitter, emitterCompleted);
            }
        });

        emitter.onTimeout(() -> {
            log.warn("[SSE] SseEmitter timed out for session {}", sessionId);
            sseTransport.safeComplete(emitter, emitterCompleted);
        });

        emitter.onCompletion(() -> log.debug("[SSE] AI chat stream completed/closed for session {}", sessionId));

        emitter.onError(e -> {
            if (sseTransport.isClientAbort(e)) {
                log.debug("[SSE] SseEmitter client disconnect for session {}: {}", sessionId, e.getMessage());
                return;
            }
            log.error("[SSE] SseEmitter error for session {}", sessionId, e);
        });

        return emitter;
    }

    private String normalizeClientMessageId(String clientMessageId) {
        if (clientMessageId == null) {
            return null;
        }
        String trimmed = clientMessageId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String latestUserMessageText(List<ChatMessage> history, String fallbackInput) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if (message instanceof UserMessage userMessage) {
                return userMessage.singleText();
            }
        }
        return fallbackInput;
    }
}
