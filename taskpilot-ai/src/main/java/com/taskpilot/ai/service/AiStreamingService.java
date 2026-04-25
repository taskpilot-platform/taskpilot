package com.taskpilot.ai.service;

import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.entity.ChatMessageEntity.SenderType;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiStreamingService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SmartRoutingService routingService;
    private final AiLogService aiLogService;
    private final ChatStreamStatusService chatStreamStatusService;
    private final SessionChatMemoryService sessionChatMemoryService;

    private static final String SYSTEM_PROMPT = """
            You are TaskPilot Copilot, an intelligent assistant for a project management system.
            You help project managers and team members with:
            - Querying task and project status
            - Recommending team member assignments based on skills and workload
            - Providing insights about project progress
            - Answering questions about the current project context
            Be concise, professional, and helpful. Always respond in the same language the user is using.
            When you perform an action (like creating a task or assigning someone), confirm what you have done.
            """;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Transactional
    public SseEmitter streamChat(Long sessionId, Long userId, String userInput, String clientMessageId) {
        ChatSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new SecurityException("Session not found or access denied"));

        SseEmitter emitter = new SseEmitter(180_000L);
        long startTime = System.currentTimeMillis();

        String effectiveClientMessageId = normalizeClientMessageId(clientMessageId);
        if (effectiveClientMessageId == null) {
            effectiveClientMessageId = UUID.randomUUID().toString();
        }

        chatStreamStatusService.upsertQueued(sessionId, userId, effectiveClientMessageId);

        Optional<ChatMessageEntity> existing = messageRepository
                .findFirstBySessionIdAndSenderAndClientMessageId(sessionId, SenderType.USER,
                        effectiveClientMessageId);
        if (existing.isPresent()) {
            log.info("[AiChat] Duplicate stream request ignored for session {} clientMessageId={}",
                    sessionId, effectiveClientMessageId);
            safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
            safeSend(emitter, "done", "", null);
            emitter.complete();
            return emitter;
        }

        messageRepository.save(ChatMessageEntity.builder()
                .sessionId(sessionId)
                .sender(SenderType.USER)
                .clientMessageId(effectiveClientMessageId)
                .content(userInput)
                .build());

        List<ChatMessage> history = sessionChatMemoryService
                .appendUserMessage(sessionId, SYSTEM_PROMPT, userInput);

        chatStreamStatusService.updatePhase(sessionId, effectiveClientMessageId,
                Phase.ROUTING, null, null, null);
        safeSend(emitter, "phase", Phase.ROUTING.name(), null);

        String contextText = history.stream().map(m -> {
            if (m instanceof UserMessage um) {
                return um.singleText();
            }
            if (m instanceof AiMessage am) {
                return am.text() != null ? am.text() : "";
            }
            if (m instanceof SystemMessage sm) {
                return sm.text();
            }
            return "";
        }).reduce("", (a, b) -> a + "\n" + b);

        String routingInput = latestUserMessageText(history, userInput);
        StreamingChatModel selectedModel = routingService.selectModel(routingInput, contextText);
        String modelName = routingService.getModelName(selectedModel);

        chatStreamStatusService.updatePhase(sessionId, effectiveClientMessageId,
                Phase.THINKING, modelName, null, null);

        String finalClientMessageId = effectiveClientMessageId;
        executor.submit(() -> {
            safeSend(emitter, "model", modelName, null);
            safeSend(emitter, "phase", Phase.THINKING.name(), null);
            doStream(emitter, session, sessionId, userId, userInput, history, selectedModel,
                    modelName, startTime, false, finalClientMessageId);
        });

        emitter.onTimeout(() -> {
            log.warn("[SSE] SseEmitter timed out for session {}", sessionId);
            emitter.complete();
        });

        emitter.onError(e -> {
            if (isClientAbort(e)) {
                log.debug("[SSE] SseEmitter client disconnect for session {}: {}", sessionId,
                        e.getMessage());
                return;
            }
            log.error("[SSE] SseEmitter error for session {}", sessionId, e);
        });

        return emitter;
    }

    private void doStream(SseEmitter emitter,
            ChatSessionEntity session,
            Long sessionId,
            Long userId,
            String userInput,
            List<ChatMessage> history,
            StreamingChatModel model,
            String modelName,
            long startTime,
            boolean isFallbackAttempt,
            String clientMessageId) {

        StringBuilder fullResponse = new StringBuilder();
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        AtomicBoolean generatingMarked = new AtomicBoolean(false);

        model.chat(history, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                fullResponse.append(partialResponse);

                if (generatingMarked.compareAndSet(false, true)) {
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.GENERATING, modelName, null, null);
                    if (!safeSend(emitter, "phase", Phase.GENERATING.name(), null)) {
                        clientDisconnected.set(true);
                    }
                }

                if (!clientDisconnected.get()) {
                    if (!safeSend(emitter, "token", Map.of("token", partialResponse),
                            MediaType.APPLICATION_JSON)) {
                        clientDisconnected.set(true);
                    }
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                long durationMs = System.currentTimeMillis() - startTime;
                String responseText = fullResponse.toString();
                int estimatedTokens = completeResponse.tokenUsage() != null
                        ? completeResponse.tokenUsage().totalTokenCount()
                        : responseText.length() / 4;

                ChatMessageEntity assistantMsg = messageRepository.save(ChatMessageEntity.builder()
                        .sessionId(sessionId)
                        .sender(SenderType.ASSISTANT)
                        .content(responseText)
                        .build());

                session.setUpdatedAt(Instant.now());
                if (session.getTitle() == null || session.getTitle().isBlank()) {
                    String autoTitle = responseText.length() > 60
                            ? responseText.substring(0, 60) + "..."
                            : responseText;
                    session.setTitle(autoTitle);
                }
                sessionRepository.save(session);

                aiLogService.saveLog(userId, null, sessionId, assistantMsg.getId(), userInput,
                        responseText, extractReasoning(responseText), null, null, modelName,
                        estimatedTokens, (int) durationMs);

                sessionChatMemoryService.appendAssistantMessage(sessionId, responseText,
                        SYSTEM_PROMPT);

                chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                        Phase.FINALIZED, modelName, assistantMsg.getId(), null);

                if (!clientDisconnected.get()) {
                    safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
                    safeSend(emitter, "done", responseText, null);
                    emitter.complete();
                }

                log.info("[SSE] Streaming complete for session {} using model {} in {}ms",
                        sessionId, modelName, durationMs);
            }

            @Override
            public void onError(Throwable error) {
                if (clientDisconnected.get() || isClientAbort(error)) {
                    log.debug("[SSE] Client aborted stream for session {} (model {}): {}",
                            sessionId, modelName, error.getMessage());
                    return;
                }

                log.error("[SSE] Model {} failed for session {}: {}", modelName, sessionId,
                        error.getMessage());

                if (!isFallbackAttempt) {
                    StreamingChatModel fallback = routingService.getFallbackModel();
                    if (fallback == model) {
                        chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                                Phase.FAILED, modelName, null, error.getMessage());
                        safeSend(emitter, "phase", Phase.FAILED.name(), null);
                        safeSend(emitter, "error",
                                "AI service is currently unavailable. Please try again later.",
                                null);
                        emitter.complete();
                        return;
                    }

                    String fallbackName = routingService.getModelName(fallback);
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.THINKING, fallbackName, null, null);
                    safeSend(emitter, "model", fallbackName + " (fallback)", null);
                    safeSend(emitter, "phase", Phase.THINKING.name(), null);

                    doStream(emitter, session, sessionId, userId, userInput, history, fallback,
                            fallbackName, startTime, true, clientMessageId);
                } else {
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.FAILED, modelName, null, error.getMessage());
                    safeSend(emitter, "phase", Phase.FAILED.name(), null);
                    safeSend(emitter, "error",
                            "AI service is currently unavailable. Please try again later.",
                            null);
                    emitter.complete();
                }
            }
        });
    }

    private boolean safeSend(SseEmitter emitter, String event, Object data, MediaType mediaType) {
        try {
            if (mediaType == null) {
                emitter.send(SseEmitter.event().name(event).data(data));
            } else {
                emitter.send(SseEmitter.event().name(event).data(data, mediaType));
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean isClientAbort(Throwable error) {
        if (error == null) {
            return false;
        }

        Throwable current = error;
        while (current != null) {
            String className = current.getClass().getName();
            if ("org.apache.catalina.connector.ClientAbortException".equals(className)
                    || "org.springframework.web.context.request.async.AsyncRequestNotUsableException"
                            .equals(className)) {
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

    private String extractReasoning(String response) {
        if (response == null) {
            return null;
        }

        int start = response.indexOf("<think>");
        int end = response.indexOf("</think>");
        if (start >= 0 && end > start) {
            return response.substring(start + 7, end).trim();
        }
        return null;
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

    private String normalizeClientMessageId(String clientMessageId) {
        if (clientMessageId == null) {
            return null;
        }
        String trimmed = clientMessageId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }}

    
    
        
        
                
                        
            
        

        
        
            
            
                    
                    
                    
                    
                    
                    
                
            
        

        
    

    
