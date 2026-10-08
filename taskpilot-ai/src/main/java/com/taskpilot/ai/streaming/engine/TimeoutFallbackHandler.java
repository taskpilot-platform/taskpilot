package com.taskpilot.ai.streaming.engine;

import com.taskpilot.ai.config.GroqMultiKeyStreamingChatModel;
import com.taskpilot.ai.config.OpenRouterMultiKeyStreamingChatModel;
import com.taskpilot.ai.context.ChatHistoryCompactor;
import com.taskpilot.ai.context.ChatMessageSanitizer;
import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.service.ChatStreamStatusService;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.ai.streaming.diagnostic.FinalizerDiagnosticOutcome;
import com.taskpilot.ai.streaming.diagnostic.FinalizerTerminalCause;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Manages streaming timeout watchdogs, multi-key retry escalation, and text-only response fallbacks.
 */
@Slf4j
@Component
public class TimeoutFallbackHandler {

    private final AiSseTransport sseTransport;
    private final ChatStreamStatusService chatStreamStatusService;
    private final SmartRoutingService routingService;
    private final ChatMessageSanitizer sanitizer;
    private final ChatHistoryCompactor compactor;
    private final ConfirmationBlockParser confirmationParser;
    private final SessionPostProcessor postProcessor;
    private final ScheduledExecutorService timeoutScheduler;

    private volatile Consumer<FinalizerDiagnosticOutcome> diagnosticOutcomeListener;

    @Value("${ai.chat.stream-first-response-timeout-seconds:60}")
    private int streamFirstResponseTimeoutSeconds = 60;

    @Value("${ai.chat.max-output-tokens:3500}")
    private int maxOutputTokens = 3500;

    @org.springframework.beans.factory.annotation.Autowired
    public TimeoutFallbackHandler(
            AiSseTransport sseTransport,
            ChatStreamStatusService chatStreamStatusService,
            SmartRoutingService routingService,
            ChatMessageSanitizer sanitizer,
            ChatHistoryCompactor compactor,
            ConfirmationBlockParser confirmationParser,
            SessionPostProcessor postProcessor) {
        this(sseTransport, chatStreamStatusService, routingService, sanitizer, compactor, confirmationParser, postProcessor,
                Executors.newScheduledThreadPool(8));
    }

    TimeoutFallbackHandler(
            AiSseTransport sseTransport,
            ChatStreamStatusService chatStreamStatusService,
            SmartRoutingService routingService,
            ChatMessageSanitizer sanitizer,
            ChatHistoryCompactor compactor,
            ConfirmationBlockParser confirmationParser,
            SessionPostProcessor postProcessor,
            ScheduledExecutorService timeoutScheduler) {
        this.sseTransport = sseTransport;
        this.chatStreamStatusService = chatStreamStatusService;
        this.routingService = routingService;
        this.sanitizer = sanitizer;
        this.compactor = compactor;
        this.confirmationParser = confirmationParser;
        this.postProcessor = postProcessor;
        this.timeoutScheduler = timeoutScheduler;
    }

    void setDiagnosticOutcomeListenerForTesting(Consumer<FinalizerDiagnosticOutcome> listener) {
        this.diagnosticOutcomeListener = listener;
    }

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
        return "I retrieved the requested data, but the AI service could not finish formatting the response. Please try again shortly.";
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
        String provider = routingService != null ? routingService.getModelProvider(textModel) : "UNKNOWN";
        log.info("[ForceTextOnly] Using text-only finalizer {} ({}) for session {}", textModelName, provider, sessionId);

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
                routingService != null && routingService.isGeminiModel(textModel)));

        ChatRequest request = ChatRequest.builder()
                .messages(textOnlyHistory)
                .maxOutputTokens(Math.min(maxOutputTokens, 1200))
                .build();

        AtomicBoolean terminalFinalized = new AtomicBoolean(false);
        AtomicInteger activeAttempt = new AtomicInteger(1);

        executeFinalizerAttempt(
                1, textModel, textModelName, provider, request,
                activeAttempt, terminalFinalized, emitter, emitterCompleted,
                session, sessionId, userId, userInput, systemPrompt, startTime,
                clientMessageId, clientDisconnected, generatingMarked,
                toolCallSummaries, toolNames);
    }

    private void executeFinalizerAttempt(
            int attempt,
            StreamingChatModel currentModel,
            String currentModelName,
            String currentProvider,
            ChatRequest request,
            AtomicInteger activeAttempt,
            AtomicBoolean terminalFinalized,
            SseEmitter emitter,
            AtomicBoolean emitterCompleted,
            ChatSessionEntity session,
            Long sessionId,
            Long userId,
            String userInput,
            String systemPrompt,
            long startTime,
            String clientMessageId,
            AtomicBoolean clientDisconnected,
            AtomicBoolean generatingMarked,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames) {

        final long stageStartedAt = System.currentTimeMillis();
        final AtomicReference<Long> firstTokenAt = new AtomicReference<>(null);
        final AtomicInteger partialChars = new AtomicInteger(0);
        final AtomicBoolean attemptClosed = new AtomicBoolean(false);
        final AtomicBoolean firstModelSignalReceived = new AtomicBoolean(false);
        final AtomicBoolean watchdogWon = new AtomicBoolean(false);
        final AtomicBoolean lateCallbackIgnored = new AtomicBoolean(false);
        final StringBuilder roundResponse = new StringBuilder();

        final ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(() -> {
            if (firstModelSignalReceived.get() || clientDisconnected.get() || emitterCompleted.get() || attempt != activeAttempt.get()) {
                return;
            }
            if (attemptClosed.compareAndSet(false, true)) {
                watchdogWon.set(true);
                long elapsedMs = System.currentTimeMillis() - stageStartedAt;
                FinalizerTerminalCause cause = FinalizerTerminalCause.FINALIZER_TIMEOUT_BEFORE_FIRST_TOKEN;
                String errorSummary = "terminalCause=" + cause;

                recordAttempt(sessionId, clientMessageId, attempt, currentProvider, currentModelName, elapsedMs, null,
                        cause, partialChars.get(), true, true, lateCallbackIgnored.get(), null);

                if (terminalFinalized.compareAndSet(false, true)) {
                    log.warn("[ForceTextOnly] Text-only finalizer timed out (attempt={}) for session {}. Emitting fallback text.", attempt, sessionId);
                    String timeoutFallback = buildTextOnlyTimeoutResponse(toolCallSummaries);
                    finalizeForceTextOnlyResponse(emitter, session, sessionId, userId, userInput, systemPrompt,
                            currentModelName, startTime, clientMessageId, clientDisconnected, toolCallSummaries, toolNames,
                            timeoutFallback, null, generatingMarked, cause, errorSummary);
                    sseTransport.safeComplete(emitter, emitterCompleted);
                }
            }
        }, 25, TimeUnit.SECONDS);

        try {
            currentModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    firstModelSignalReceived.set(true);
                    if (attemptClosed.get() || clientDisconnected.get() || emitterCompleted.get() || attempt != activeAttempt.get()) {
                        lateCallbackIgnored.set(true);
                        log.warn("[AI_ATTEMPT_LATE] session={} clientMessageId={} stage=FINALIZER late partial response ignored (attempt={}, chars={})",
                                sessionId, clientMessageId, attempt, partialResponse != null ? partialResponse.length() : 0);
                        return;
                    }
                    if (firstTokenAt.get() == null) {
                        firstTokenAt.set(System.currentTimeMillis());
                    }
                    if (partialResponse != null) {
                        partialChars.addAndGet(partialResponse.length());
                    }
                    roundResponse.append(partialResponse);
                    sseTransport.sendTokenToClient(emitter, partialResponse, clientDisconnected, generatingMarked,
                            sessionId, clientMessageId, currentModelName);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    timeoutFuture.cancel(true);
                    if (!attemptClosed.compareAndSet(false, true) || attempt != activeAttempt.get()) {
                        lateCallbackIgnored.set(true);
                        log.warn("[AI_ATTEMPT_LATE] session={} clientMessageId={} stage=FINALIZER late complete response ignored (attempt={})",
                                sessionId, clientMessageId, attempt);
                        return;
                    }
                    long elapsedMs = System.currentTimeMillis() - stageStartedAt;
                    Long firstTokenMs = firstTokenAt.get() != null ? (firstTokenAt.get() - stageStartedAt) : null;
                    boolean isEmpty = roundResponse.isEmpty() || roundResponse.toString().trim().isEmpty();
                    FinalizerTerminalCause cause = isEmpty
                            ? FinalizerTerminalCause.FINALIZER_COMPLETED_EMPTY
                            : FinalizerTerminalCause.FINALIZER_COMPLETED_WITH_TEXT;

                    recordAttempt(sessionId, clientMessageId, attempt, currentProvider, currentModelName, elapsedMs, firstTokenMs,
                            cause, partialChars.get(), false, true, lateCallbackIgnored.get(), null);

                    if (terminalFinalized.compareAndSet(false, true)) {
                        finalizeForceTextOnlyResponse(emitter, session, sessionId, userId, userInput, systemPrompt,
                                currentModelName, startTime, clientMessageId, clientDisconnected, toolCallSummaries, toolNames,
                                roundResponse.toString(), completeResponse, generatingMarked, cause, null);
                        sseTransport.safeComplete(emitter, emitterCompleted);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    timeoutFuture.cancel(true);
                    if (!attemptClosed.compareAndSet(false, true) || attempt != activeAttempt.get()) {
                        lateCallbackIgnored.set(true);
                        log.warn("[AI_ATTEMPT_LATE] session={} clientMessageId={} stage=FINALIZER late error response ignored (attempt={}): {}",
                                sessionId, clientMessageId, attempt, error != null ? error.getMessage() : "null");
                        return;
                    }
                    long elapsedMs = System.currentTimeMillis() - stageStartedAt;
                    Long firstTokenMs = firstTokenAt.get() != null ? (firstTokenAt.get() - stageStartedAt) : null;
                    boolean hadTokens = partialChars.get() > 0 || firstTokenAt.get() != null;
                    Throwable actionable = unwrapActionableException(error);

                    boolean canFallback = (attempt == 1) && !hadTokens && isRateLimitError(error);
                    StreamingChatModel fallbackCandidate = canFallback && routingService != null
                            ? routingService.getNextStreamingFallback(currentModel)
                            : currentModel;

                    if (canFallback && fallbackCandidate != null && fallbackCandidate != currentModel) {
                        FinalizerTerminalCause cause = FinalizerTerminalCause.FINALIZER_MODEL_ERROR_BEFORE_FIRST_TOKEN;
                        recordAttempt(sessionId, clientMessageId, 1, currentProvider, currentModelName, elapsedMs, firstTokenMs,
                                cause, partialChars.get(), false, false, lateCallbackIgnored.get(), actionable);

                        String fallbackModelName = routingService.getModelName(fallbackCandidate);
                        String fallbackProvider = routingService.getModelProvider(fallbackCandidate);
                        log.warn("[ForceTextOnly] Finalizer attempt 1 ({}) hit rate limit before first token. Escalating to fallback text model: {} ({})",
                                currentModelName, fallbackModelName, fallbackProvider);

                        activeAttempt.set(2);
                        executeFinalizerAttempt(
                                2, fallbackCandidate, fallbackModelName, fallbackProvider, request,
                                activeAttempt, terminalFinalized, emitter, emitterCompleted,
                                session, sessionId, userId, userInput, systemPrompt, startTime,
                                clientMessageId, clientDisconnected, generatingMarked,
                                toolCallSummaries, toolNames);
                        return;
                    }

                    FinalizerTerminalCause cause = hadTokens
                            ? FinalizerTerminalCause.FINALIZER_MODEL_ERROR_AFTER_PARTIAL
                            : FinalizerTerminalCause.FINALIZER_MODEL_ERROR_BEFORE_FIRST_TOKEN;
                    String errorSummary = buildPersistedDiagnosticSummary(cause, error);

                    recordAttempt(sessionId, clientMessageId, attempt, currentProvider, currentModelName, elapsedMs, firstTokenMs,
                            cause, partialChars.get(), false, true, lateCallbackIgnored.get(), actionable);

                    log.warn("[ForceTextOnly] Error in text-only finalizer (attempt={}) for session {}: {}",
                            attempt, sessionId, error != null ? error.getMessage() : "null");

                    if (terminalFinalized.compareAndSet(false, true)) {
                        String timeoutFallback = buildTextOnlyTimeoutResponse(toolCallSummaries);
                        finalizeForceTextOnlyResponse(emitter, session, sessionId, userId, userInput, systemPrompt,
                                currentModelName, startTime, clientMessageId, clientDisconnected, toolCallSummaries, toolNames,
                                timeoutFallback, null, generatingMarked, cause, errorSummary);
                        sseTransport.safeComplete(emitter, emitterCompleted);
                    }
                }
            });
        } catch (Exception ex) {
            timeoutFuture.cancel(true);
            if (!attemptClosed.compareAndSet(false, true) || attempt != activeAttempt.get()) {
                return;
            }
            long elapsedMs = System.currentTimeMillis() - stageStartedAt;
            Throwable actionable = unwrapActionableException(ex);

            boolean canFallback = (attempt == 1) && isRateLimitError(ex);
            StreamingChatModel fallbackCandidate = canFallback && routingService != null
                    ? routingService.getNextStreamingFallback(currentModel)
                    : currentModel;

            if (canFallback && fallbackCandidate != null && fallbackCandidate != currentModel) {
                FinalizerTerminalCause cause = FinalizerTerminalCause.FINALIZER_SYNC_START_FAILURE;
                recordAttempt(sessionId, clientMessageId, 1, currentProvider, currentModelName, elapsedMs, null,
                        cause, partialChars.get(), false, false, lateCallbackIgnored.get(), actionable);

                String fallbackModelName = routingService.getModelName(fallbackCandidate);
                String fallbackProvider = routingService.getModelProvider(fallbackCandidate);
                log.warn("[ForceTextOnly] Finalizer attempt 1 ({}) hit synchronous rate limit. Escalating to fallback text model: {} ({})",
                        currentModelName, fallbackModelName, fallbackProvider);

                activeAttempt.set(2);
                executeFinalizerAttempt(
                        2, fallbackCandidate, fallbackModelName, fallbackProvider, request,
                        activeAttempt, terminalFinalized, emitter, emitterCompleted,
                        session, sessionId, userId, userInput, systemPrompt, startTime,
                        clientMessageId, clientDisconnected, generatingMarked,
                        toolCallSummaries, toolNames);
                return;
            }

            FinalizerTerminalCause cause = FinalizerTerminalCause.FINALIZER_SYNC_START_FAILURE;
            String errorSummary = buildPersistedDiagnosticSummary(cause, ex);

            recordAttempt(sessionId, clientMessageId, attempt, currentProvider, currentModelName, elapsedMs, null,
                    cause, partialChars.get(), false, true, lateCallbackIgnored.get(), actionable);

            log.warn("[ForceTextOnly] Synchronous exception starting text-only finalizer (attempt={}) for session {}: {}",
                    attempt, sessionId, ex.getMessage());

            if (terminalFinalized.compareAndSet(false, true)) {
                String timeoutFallback = buildTextOnlyTimeoutResponse(toolCallSummaries);
                finalizeForceTextOnlyResponse(emitter, session, sessionId, userId, userInput, systemPrompt,
                        currentModelName, startTime, clientMessageId, clientDisconnected, toolCallSummaries, toolNames,
                        timeoutFallback, null, generatingMarked, cause, errorSummary);
                sseTransport.safeComplete(emitter, emitterCompleted);
            }
        }
    }

    private Throwable unwrapActionableException(Throwable error) {
        Throwable current = error;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            if ((current instanceof java.util.concurrent.CompletionException
                    || current instanceof java.util.concurrent.ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
            } else {
                break;
            }
        }
        return current != null ? current : error;
    }

    private boolean isRateLimitError(Throwable error) {
        Throwable current = error;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            String className = current.getClass().getName();
            if (className.endsWith("RateLimitException")) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("429") || lower.contains("rate limit") || lower.contains("rate_limit")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void recordAttempt(
            Long sessionId,
            String clientMessageId,
            int attempt,
            String provider,
            String modelName,
            long elapsedMs,
            Long firstTokenMs,
            FinalizerTerminalCause cause,
            int partialChars,
            boolean watchdogWon,
            boolean finalizationExecuted,
            boolean lateCallbackIgnored,
            Throwable error) {

        log.info("[AI_ATTEMPT] session={} clientMessageId={} stage=FINALIZER attempt={} provider={} model={} mode=TEXT elapsedMs={} firstTokenMs={} terminalCause={} partialChars={} watchdogWon={} finalizationExecuted={} lateCallbackIgnored={}",
                sessionId, clientMessageId, attempt, provider, modelName, elapsedMs, firstTokenMs != null ? firstTokenMs : "none",
                cause, partialChars, watchdogWon, finalizationExecuted, lateCallbackIgnored);

        Consumer<FinalizerDiagnosticOutcome> listener = this.diagnosticOutcomeListener;
        if (listener != null) {
            listener.accept(new FinalizerDiagnosticOutcome(
                    sessionId, clientMessageId, "FINALIZER", attempt, provider, modelName, "TEXT",
                    elapsedMs, firstTokenMs, cause, partialChars, watchdogWon, finalizationExecuted,
                    lateCallbackIgnored, error));
        }
    }

    private String buildPersistedDiagnosticSummary(FinalizerTerminalCause cause, Throwable t) {
        if (t == null) {
            return "terminalCause=" + cause;
        }
        Throwable actionable = unwrapActionableException(t);
        return "terminalCause=" + cause + "; exceptionClass=" + actionable.getClass().getName();
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
        finalizeForceTextOnlyResponse(emitter, session, sessionId, userId, userInput, systemPrompt,
                modelName, startTime, clientMessageId, clientDisconnected, toolCallSummaries, toolNames,
                rawResponseText, completeResponse, generatingMarked, null, null);
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
            AtomicBoolean generatingMarked,
            FinalizerTerminalCause terminalCause,
            String errorSummary) {
        String responseText = confirmationParser.appendTaskPilotBlocks(
                sanitizer.stripToolCallJson(sanitizer.stripThinkBlocks(rawResponseText)), toolCallSummaries);
        String extractedReasoning = sanitizer.extractAllThinkBlocks(rawResponseText);

        if (responseText == null || responseText.isBlank()) {
            responseText = "Mình chưa tạo được câu trả lời hoàn chỉnh. Bạn thử gửi lại yêu cầu ngắn hơn một chút nhé.";
        }

        long durationMs = System.currentTimeMillis() - startTime;
        boolean hasProviderUsage = completeResponse != null && completeResponse.tokenUsage() != null;
        int estimatedTokens = hasProviderUsage
                ? completeResponse.tokenUsage().totalTokenCount()
                : responseText.length() / 4;

        if (hasProviderUsage) {
            log.info("[TokenAccounting] session={} tokens={} (providerReportedTokens)", sessionId, estimatedTokens);
        } else {
            log.info("[TokenAccounting] session={} tokens={} (locallyEstimatedResponseTokens from response length {}; unavailable upstream usage)",
                    sessionId, estimatedTokens, responseText.length());
        }

        if (generatingMarked.compareAndSet(false, true)) {
            sseTransport.safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
        }

        if (!clientDisconnected.get()) {
            sseTransport.safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
            sseTransport.safeSend(emitter, "done", responseText, null);
        }

        postProcessor.saveSessionMessagesAndLogsAsync(session, sessionId, userId, userInput, systemPrompt,
                responseText, extractedReasoning, toolNames, toolCallSummaries.isEmpty() ? null : toolCallSummaries,
                modelName, estimatedTokens, durationMs, clientMessageId, errorSummary);

        log.info("[SSE] forceTextOnly immediately released client stream for session {} via {} (terminalCause={})",
                sessionId, modelName, terminalCause != null ? terminalCause.name() : "NONE");
    }
}
