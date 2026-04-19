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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiStreamingService {
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SmartRoutingService routingService;
    private final AiLogService aiLogService;
    @Value("${ai.chat.history-size:20}")
    private int historySize;
    private static final String SYSTEM_PROMPT =
            """
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
        List<ChatMessage> history = buildHistory(sessionId, userInput);
        String contextText = history.stream().map(m -> {
            if (m instanceof UserMessage um)
                return um.singleText();
            if (m instanceof AiMessage am)
                return am.text() != null ? am.text() : "";
            if (m instanceof SystemMessage sm)
                return sm.text();
            return "";
        }).reduce("", (a, b) -> a + "\n" + b);
        StreamingChatModel selectedModel = routingService.selectModel(userInput, contextText);
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
        emitter.onError(e -> log.error("[SSE] SseEmitter error for session {}", sessionId, e));
        return emitter;
    }

    private void doStream(SseEmitter emitter, ChatSessionEntity session, Long sessionId,
            Long userId, String userInput, List<ChatMessage> history, StreamingChatModel model,
            String modelName, long startTime, boolean isFallbackAttempt) {
        StringBuilder fullResponse = new StringBuilder();
        CompletableFuture<Void> future = new CompletableFuture<>();
        model.chat(history, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    fullResponse.append(partialResponse);
                    emitter.send(SseEmitter.event().name("token").data(partialResponse));
                } catch (IOException e) {
                    log.debug("[SSE] Client disconnected during streaming for session {}",
                            sessionId);
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
                    ChatMessageEntity assistantMsg =
                            messageRepository.save(ChatMessageEntity.builder().sessionId(sessionId)
                                    .sender(SenderType.ASSISTANT).content(responseText).build());
                    session.setUpdatedAt(Instant.now());
                    if (session.getTitle() == null || session.getTitle().isBlank()) {
                        String autoTitle =
                                responseText.length() > 60 ? responseText.substring(0, 60) + "..."
                                        : responseText;
                        session.setTitle(autoTitle);
                    }
                    sessionRepository.save(session);
                    aiLogService.saveLog(userId, null, sessionId, assistantMsg.getId(), userInput,
                            responseText, extractReasoning(responseText), null, null, modelName,
                            estimatedTokens, (int) durationMs);
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
                log.error("[SSE] Model {} failed for session {}: {}", modelName, sessionId,
                        error.getMessage());
                if (!isFallbackAttempt) {
                    StreamingChatModel fallback = routingService.getFallbackModel();
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

    private List<ChatMessage> buildHistory(Long sessionId, String currentUserMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        PageRequest lastN = PageRequest.of(0, historySize);
        List<ChatMessageEntity> dbMessages =
                messageRepository.findLastNBySessionId(sessionId, lastN);
        for (ChatMessageEntity msg : dbMessages) {
            if (msg.getSender() == SenderType.USER) {
                messages.add(UserMessage.from(msg.getContent()));
            } else if (msg.getSender() == SenderType.ASSISTANT) {
                messages.add(AiMessage.from(msg.getContent()));
            }
        }
        messages.add(UserMessage.from(currentUserMessage));
        return messages;
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
}

