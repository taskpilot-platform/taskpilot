package com.taskpilot.ai.service;

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
import java.util.concurrent.CompletableFuture;
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
    public SseEmitter streamChat(Long sessionId, Long userId, String userInput) {
        ChatSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new SecurityException("Session not found or access denied"));
        SseEmitter emitter = new SseEmitter(180_000L);
        long startTime = System.currentTimeMillis();
        messageRepository.save(ChatMessageEntity.builder().sessionId(sessionId)
                .sender(SenderType.USER).content(userInput).build());
        List<ChatMessage> history = sessionChatMemoryService
                .appendUserMessage(sessionId, SYSTEM_PROMPT, userInput);
        String contextText = history.stream().map(m -> {
            if (m instanceof UserMessage um)
                return um.singleText();
            if (m instanceof AiMessage am)
                return am.text() != null ? am.text() : "";
            if (m instanceof SystemMessage sm)
                return sm.text();
            return "";
        }).reduce("", (a, b) -> a + "\n" + b);
        String routingInput = latestUserMessageText(history, userInput);
        StreamingChatModel selectedModel = routingService.selectModel(routingInput, contextText);
        String modelName = routingService.getModelName(selectedModel);
        executor.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("model").data(modelName));
                doStream(emitter, session, sessionId, userId, userInput, history, selectedModel,
                        modelName, startTime, false);
            } catch (IOException e) {
                log.warn("[SSE] Emitter closed before streaming started for session {}", sessionId);
            }
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

    private void doStream(SseEmitter emitter, ChatSessionEntity session, Long sessionId,
            Long userId, String userInput, List<ChatMessage> history, StreamingChatModel model,
            String modelName, long startTime, boolean isFallbackAttempt) {
        StringBuilder fullResponse = new StringBuilder();
        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        model.chat(history, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    fullResponse.append(partialResponse);
                    // Send token as JSON to preserve leading spaces from model chunks.
                    emitter.send(SseEmitter.event().name("token")
                            .data(Map.of("token", partialResponse), MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    clientDisconnected.set(true);
                    log.debug("[SSE] Client disconnected during streaming for session {}",
                            sessionId);
                    emitter.complete();
                    future.complete(null);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                try {
                    long durationMs = System.currentTimeMillis() - startTime;
                    String responseText = fullResponse.toString();
                    int estimatedTokens = completeResponse.tokenUsage() != null
                            ? completeResponse.tokenUsage().totalTokenCount()
                            : responseText.length() / 4;
                    ChatMessageEntity assistantMsg = messageRepository
                            .save(ChatMessageEntity.builder().sessionId(sessionId)
                                    .sender(SenderType.ASSISTANT).content(responseText).build());
                    session.setUpdatedAt(Instant.now());
                    if (session.getTitle() == null || session.getTitle().isBlank()) {
                        String autoTitle = responseText.length() > 60 ? responseText.substring(0, 60) + "..."
                                : responseText;
                        session.setTitle(autoTitle);
                    }
                    sessionRepository.save(session);
                    aiLogService.saveLog(userId, null, sessionId, assistantMsg.getId(), userInput,
                            responseText, extractReasoning(responseText), null, null, modelName,
                            estimatedTokens, (int) durationMs);
                    sessionChatMemoryService.appendAssistantMessage(sessionId, responseText,
                            SYSTEM_PROMPT);
                    emitter.send(SseEmitter.event().name("done").data(responseText));
                    emitter.complete();
                    log.info("[SSE] Streaming complete for session {} using model {} in {}ms",
                            sessionId, modelName, durationMs);
                } catch (IOException e) {
                    log.debug("[SSE] Client disconnected at completion for session {}", sessionId);
                } finally {
                    future.complete(null);
                }
            }

            @Override
            public void onError(Throwable error) {
                if (clientDisconnected.get() || isClientAbort(error)) {
                    log.warn("[SSE] Client aborted stream for session {} (model {}): {}",
                            sessionId, modelName, error.getMessage());
                    emitter.complete();
                    future.complete(null);
                    return;
                }

                log.error("[SSE] Model {} failed for session {}: {}", modelName, sessionId,
                        error.getMessage());
                if (!isFallbackAttempt) {
                    StreamingChatModel fallback = routingService.getFallbackModel();
                    if (fallback == model) {
                        log.warn(
                                "[SSE] Fallback model is identical to current model ({}), skip fallback for session {}",
                                modelName, sessionId);
                        try {
                            emitter.send(SseEmitter.event().name("error").data(
                                    "AI service is currently unavailable. Please try again later."));
                            emitter.complete();
                        } catch (IOException ignored) {
                        }
                        future.completeExceptionally(error);
                        return;
                    }
                    String fallbackName = routingService.getModelName(fallback);
                    log.info("[SSE] Falling back to {} for session {}", fallbackName, sessionId);
                    try {
                        emitter.send(SseEmitter.event().name("model")
                                .data(fallbackName + " (fallback)"));
                    } catch (IOException ignored) {
                    }
                    doStream(emitter, session, sessionId, userId, userInput, history, fallback,
                            fallbackName, startTime, true);
                } else {
                    try {
                        emitter.send(SseEmitter.event().name("error").data(
                                "AI service is currently unavailable. Please try again later."));
                        emitter.complete();
                    } catch (IOException ignored) {
                    }
                    future.completeExceptionally(error);
                }
            }
        });
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
        if (response == null)
            return null;
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
}
