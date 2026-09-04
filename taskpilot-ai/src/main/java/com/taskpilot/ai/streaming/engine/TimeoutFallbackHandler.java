package com.taskpilot.ai.streaming.engine;

import com.taskpilot.ai.config.GroqMultiKeyStreamingChatModel;
import com.taskpilot.ai.config.OpenRouterMultiKeyStreamingChatModel;
import com.taskpilot.ai.context.ChatHistoryCompactor;
import com.taskpilot.ai.context.ChatMessageSanitizer;
import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.service.ChatStreamStatusService;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.ai.streaming.postprocess.SessionPostProcessor;
import com.taskpilot.ai.streaming.sse.AiSseTransport;
import com.taskpilot.ai.streaming.tool.ConfirmationBlockParser;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages streaming timeout watchdogs, multi-key retry escalation, and text-only response fallbacks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutFallbackHandler {

    private final AiSseTransport sseTransport;
    private final ChatStreamStatusService chatStreamStatusService;
    private final SmartRoutingService routingService;
    private final ChatMessageSanitizer sanitizer;
    private final ChatHistoryCompactor compactor;
    private final ConfirmationBlockParser confirmationParser;
    private final SessionPostProcessor postProcessor;

    @Value("${ai.chat.stream-first-response-timeout-seconds:60}")
    private int streamFirstResponseTimeoutSeconds = 60;

    @Value("${ai.chat.max-output-tokens:3500}")
    private int maxOutputTokens = 3500;

    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(8);

    @PreDestroy
    public void shutdown() {
        timeoutScheduler.shutdownNow();
    }

    public ScheduledExecutorService getTimeoutScheduler() {
        return timeoutScheduler;
    }

    public boolean hasRemainingKeys(StreamingChatModel model, int modelKeyAttempts) {
        if (model instanceof OpenRouterMultiKeyStreamingChatModel openRouterModel) {
            return modelKeyAttempts < openRouterModel.keyCount();
        }
        if (model instanceof GroqMultiKeyStreamingChatModel groqModel) {
            return modelKeyAttempts < groqModel.keyCount();
        }
        return false;
    }

    public String getModelKeyLabel(StreamingChatModel model, int attempt) {
        if (model instanceof OpenRouterMultiKeyStreamingChatModel openRouterModel) {
            return "next OpenRouter key " + attempt + "/" + openRouterModel.keyCount();
        }
        if (model instanceof GroqMultiKeyStreamingChatModel groqModel) {
            return "next Groq key " + attempt + "/" + groqModel.keyCount();
        }
        return "key " + attempt;
    }

    public String buildTextOnlyTimeoutResponse(List<Map<String, Object>> toolCallSummaries) {
        boolean hasPendingConfirmation = toolCallSummaries != null && toolCallSummaries.stream()
                .anyMatch(summary -> summary.get("confirmation") instanceof Map<?, ?>);
        if (hasPendingConfirmation) {
            return "Mình đã chuẩn bị thao tác ghi dữ liệu và cần bạn phê duyệt trong thẻ xác nhận bên dưới. "
                    + "Bước diễn giải cuối của model phản hồi quá lâu nên mình hiển thị ngay hành động cần xác nhận.";
        }
        return "Mình đã lấy dữ liệu bằng công cụ nội bộ, nhưng bước diễn giải cuối của model phản hồi quá lâu. "
                + "Bạn thử gửi lại yêu cầu ngắn hơn hoặc yêu cầu phân công trực tiếp cho một task cụ thể nhé.";
    }

    public void forceTextOnlyResponse(
            SseEmitter emitter,
            AtomicBoolean emitterCompleted,
            ChatSessionEntity session,
            Long sessionId,
            Long userId,
            String userInput,
            List<ChatMessage> history,
            String systemPrompt,
            StreamingChatModel model,
            String modelName,
            long startTime,
            boolean isFallbackAttempt,
            String clientMessageId,
            StringBuilder ignoredSharedBuffer,
            AtomicBoolean clientDisconnected,
            AtomicBoolean generatingMarked,
            boolean requiresAHP,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames,
            String guardrailInstruction) {

        StreamingChatModel textModel = model;
        String textModelName = modelName;
        log.info("[ForceTextOnly] Using text-only finalizer {} for session {}", textModelName, sessionId);

        List<ChatMessage> textOnlyHistory = new ArrayList<>(sanitizer.sanitizeHistoryForTools(history));
        if (!textOnlyHistory.isEmpty() && textOnlyHistory.get(0) instanceof SystemMessage) {
            textOnlyHistory.set(0, SystemMessage.from("""
                You are the Assistant of the TaskPilot system. Your purpose is to answer the user's question directly and concisely in Vietnamese based on the provided tool results in the conversation history.
                DO NOT write any thinking process or explanation inside <think> or <thought> tags. Provide your final answer in Vietnamese directly and concisely to optimize response speed.
                DO NOT call any tools.
                """));
        }
        textOnlyHistory.add(SystemMessage.from(guardrailInstruction));
        textOnlyHistory = new ArrayList<>(sanitizer.cleanAndAlternateRoles(
                compactor.compactHistoryForRequest(textOnlyHistory, "text-only"),
                routingService.isGeminiModel(textModel)));

        StringBuilder roundResponse = new StringBuilder();
        ChatRequest request = ChatRequest.builder()
                .messages(textOnlyHistory)
                .maxOutputTokens(Math.min(maxOutputTokens, 1200))
                .build();

        final AtomicBoolean roundClosed = new AtomicBoolean(false);
        final AtomicBoolean firstModelSignalReceived = new AtomicBoolean(false);
        final ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(() -> {
            if (firstModelSignalReceived.get() || clientDisconnected.get() || emitterCompleted.get()) {
                return;
            }
            if (roundClosed.compareAndSet(false, true)) {
                log.warn("[ForceTextOnly] Text-only finalizer timed out for session {}. Emitting fallback text.", sessionId);
                String timeoutFallback = buildTextOnlyTimeoutResponse(toolCallSummaries);
                finalizeForceTextOnlyResponse(emitter, session, sessionId, userId, userInput, systemPrompt,
                        textModelName, startTime, clientMessageId, clientDisconnected, toolCallSummaries, toolNames,
                        timeoutFallback, null, generatingMarked);
                sseTransport.safeComplete(emitter, emitterCompleted);
            }
        }, 25, TimeUnit.SECONDS);

        try {
            textModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    firstModelSignalReceived.set(true);
                    if (roundClosed.get() || clientDisconnected.get() || emitterCompleted.get()) {
                        return;
                    }
                    roundResponse.append(partialResponse);
                    sseTransport.sendTokenToClient(emitter, partialResponse, clientDisconnected, generatingMarked,
                            sessionId, clientMessageId, textModelName);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    timeoutFuture.cancel(true);
                    if (roundClosed.compareAndSet(false, true)) {
                        finalizeForceTextOnlyResponse(emitter, session, sessionId, userId, userInput, systemPrompt,
                                textModelName, startTime, clientMessageId, clientDisconnected, toolCallSummaries, toolNames,
                                roundResponse.toString(), completeResponse, generatingMarked);
                        sseTransport.safeComplete(emitter, emitterCompleted);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    timeoutFuture.cancel(true);
                    if (roundClosed.compareAndSet(false, true)) {
                        log.warn("[ForceTextOnly] Error in text-only finalizer for session {}: {}", sessionId, error.getMessage());
                        String timeoutFallback = buildTextOnlyTimeoutResponse(toolCallSummaries);
                        finalizeForceTextOnlyResponse(emitter, session, sessionId, userId, userInput, systemPrompt,
                                textModelName, startTime, clientMessageId, clientDisconnected, toolCallSummaries, toolNames,
                                timeoutFallback, null, generatingMarked);
                        sseTransport.safeComplete(emitter, emitterCompleted);
                    }
                }
            });
        } catch (Exception ex) {
            timeoutFuture.cancel(true);
            if (roundClosed.compareAndSet(false, true)) {
                String timeoutFallback = buildTextOnlyTimeoutResponse(toolCallSummaries);
                finalizeForceTextOnlyResponse(emitter, session, sessionId, userId, userInput, systemPrompt,
                        textModelName, startTime, clientMessageId, clientDisconnected, toolCallSummaries, toolNames,
                        timeoutFallback, null, generatingMarked);
                sseTransport.safeComplete(emitter, emitterCompleted);
            }
        }
    }

    public void finalizeForceTextOnlyResponse(
            SseEmitter emitter,
            ChatSessionEntity session,
            Long sessionId,
            Long userId,
            String userInput,
            String systemPrompt,
            String modelName,
            long startTime,
            String clientMessageId,
            AtomicBoolean clientDisconnected,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames,
            String rawResponseText,
            ChatResponse completeResponse,
            AtomicBoolean generatingMarked) {
        String responseText = confirmationParser.appendTaskPilotBlocks(
                sanitizer.stripToolCallJson(sanitizer.stripThinkBlocks(rawResponseText)), toolCallSummaries);
        String extractedReasoning = sanitizer.extractAllThinkBlocks(rawResponseText);

        if (responseText == null || responseText.isBlank()) {
            responseText = "Mình chưa tạo được câu trả lời hoàn chỉnh. Bạn thử gửi lại yêu cầu ngắn hơn một chút nhé.";
        }

        long durationMs = System.currentTimeMillis() - startTime;
        int estimatedTokens = completeResponse != null && completeResponse.tokenUsage() != null
                ? completeResponse.tokenUsage().totalTokenCount()
                : responseText.length() / 4;

        if (generatingMarked.compareAndSet(false, true)) {
            sseTransport.safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
        }

        if (!clientDisconnected.get()) {
            sseTransport.safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
            sseTransport.safeSend(emitter, "done", responseText, null);
        }

        postProcessor.saveSessionMessagesAndLogsAsync(session, sessionId, userId, userInput, systemPrompt,
                responseText, extractedReasoning, toolNames, toolCallSummaries.isEmpty() ? null : toolCallSummaries,
                modelName, estimatedTokens, durationMs, clientMessageId);

        log.info("[SSE] forceTextOnly immediately released client stream for session {} via {}", sessionId, modelName);
    }
}
