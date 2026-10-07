package com.taskpilot.ai.streaming.engine;

import com.taskpilot.ai.context.ChatHistoryCompactor;
import com.taskpilot.ai.context.ChatMessageSanitizer;
import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.service.ChatStreamStatusService;
import com.taskpilot.ai.service.SessionChatMemoryService;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.ai.streaming.diagnostic.IntermediateDiagnosticOutcome;
import com.taskpilot.ai.streaming.diagnostic.IntermediateTerminalCause;
import com.taskpilot.ai.streaming.sse.AiSseTransport;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Streams intermediate summaries via reasoning text model after Round 0 tool execution
 * before proceeding to subsequent execution rounds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntermediateResponseStreamer {

    private final AiSseTransport sseTransport;
    private final ChatMessageSanitizer sanitizer;
    private final ChatHistoryCompactor compactor;
    private final SmartRoutingService routingService;
    private final SessionChatMemoryService sessionChatMemoryService;
    private final ChatStreamStatusService chatStreamStatusService;

    private volatile Consumer<IntermediateDiagnosticOutcome> diagnosticOutcomeListener;

    void setDiagnosticOutcomeListenerForTesting(Consumer<IntermediateDiagnosticOutcome> listener) {
        this.diagnosticOutcomeListener = listener;
    }

    public void streamIntermediateResponseAndContinue(
            SseEmitter emitter,
            Long sessionId,
            String userInput,
            List<ChatMessage> history,
            String systemPrompt,
            String modelName,
            String clientMessageId,
            StringBuilder fullResponse,
            AtomicBoolean clientDisconnected,
            AtomicBoolean generatingMarked,
            String toolResultsText,
            ScheduledExecutorService timeoutScheduler,
            Runnable continueToNextRound) {

        log.info("[Multi-Agent] Round 0 complete. Streaming intermediate response via reasoning text model before next round for session {}", sessionId);
        try {
            emitter.send(SseEmitter.event().id(clientMessageId).name("status").data("🟢 Đang tóm tắt kết quả đợt 1..."));
        } catch (Exception ignored) {
        }

        if (generatingMarked.compareAndSet(false, true)) {
            sseTransport.safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
            sseTransport.safeSend(emitter, "phase", Phase.GENERATING.name(), null);
        }

        String promptForCommunicator = "Đây là thông tin hệ thống vừa truy vấn được từ Database cho yêu cầu đầu tiên của người dùng. "
                + "Hãy báo cáo đầy đủ, thân thiện các thông tin này (như danh sách thông báo chưa đọc, dự án gần nhất, thành viên dự án...) cho người dùng bằng tiếng Việt. "
                + "Sau đó, báo cho người dùng biết là bạn đang chuẩn bị thực hiện bước tiếp theo của yêu cầu (như tạo công việc hoặc chỉnh sửa thông tin). "
                + "TUYỆT ĐỐI KHÔNG sinh ra thẻ <think> hay bất kỳ quá trình suy nghĩ nào khác. Trả lời trực tiếp vào nội dung: \n" + toolResultsText;

        List<ChatMessage> textOnlyHistory = new ArrayList<>(sanitizer.sanitizeHistoryForTools(history));
        if (!textOnlyHistory.isEmpty() && textOnlyHistory.get(0) instanceof SystemMessage) {
            textOnlyHistory.set(0, SystemMessage.from(
                    "You are the Assistant of the TaskPilot system. Your purpose is to report findings from Database directly and concisely in Vietnamese. DO NOT write any thinking process or call tools."));
        }
        textOnlyHistory.add(SystemMessage.from(promptForCommunicator));
        textOnlyHistory = new ArrayList<>(sanitizer.cleanAndAlternateRoles(
                compactor.compactHistoryForRequest(textOnlyHistory, "intermediate-text"),
                true));

        StreamingChatModel reasoningModel = routingService != null ? routingService.getReasoningTextModel() : null;
        String reasoningModelName = (routingService != null && reasoningModel != null)
                ? routingService.getModelName(reasoningModel) : (modelName != null ? modelName : "Unknown");
        String provider = (routingService != null && reasoningModel != null)
                ? routingService.getModelProvider(reasoningModel) : "UNKNOWN";

        ChatRequest request = ChatRequest.builder()
                .messages(textOnlyHistory)
                .maxOutputTokens(800)
                .build();

        StringBuilder intermediateResponse = new StringBuilder();
        final long stageStartedAt = System.currentTimeMillis();
        final AtomicReference<Long> firstTokenAt = new AtomicReference<>(null);
        final AtomicInteger partialChars = new AtomicInteger(0);
        final AtomicBoolean roundFinished = new AtomicBoolean(false);
        final AtomicBoolean watchdogWon = new AtomicBoolean(false);
        final AtomicBoolean continuationExecuted = new AtomicBoolean(false);
        final AtomicBoolean lateCallbackIgnored = new AtomicBoolean(false);

        final ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(() -> {
            if (roundFinished.compareAndSet(false, true)) {
                watchdogWon.set(true);
                long elapsedMs = System.currentTimeMillis() - stageStartedAt;
                boolean hadTokens = partialChars.get() > 0 || firstTokenAt.get() != null;
                IntermediateTerminalCause cause = hadTokens
                        ? IntermediateTerminalCause.INTERMEDIATE_TIMEOUT_AFTER_PARTIAL
                        : IntermediateTerminalCause.INTERMEDIATE_TIMEOUT_BEFORE_FIRST_TOKEN;

                recordAttempt(sessionId, clientMessageId, 1, provider, reasoningModelName, elapsedMs,
                        firstTokenAt.get() != null ? (firstTokenAt.get() - stageStartedAt) : null,
                        cause, partialChars.get(), true, true, lateCallbackIgnored.get(), null);

                log.warn("[Intermediate] Timeout occurred during intermediate reasoning model streaming for session {}. Continuing to next round.", sessionId);
                generatingMarked.set(false);
                sseTransport.safeSend(emitter, "token", Map.of("token", "\n\n<think>\nTiếp tục thực hiện bước tiếp theo...\n</think>\n\n"), MediaType.APPLICATION_JSON);
                sseTransport.safeSend(emitter, "phase", Phase.THINKING.name(), null);

                continuationExecuted.set(true);
                continueToNextRound.run();
            }
        }, 30, TimeUnit.SECONDS);

        final AtomicBoolean insideLlmThink = new AtomicBoolean(false);
        final StringBuilder filterBuffer = new StringBuilder();

        try {
            if (reasoningModel == null) {
                throw new IllegalStateException("No reasoning model available for intermediate streaming");
            }

            reasoningModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    if (roundFinished.get()) {
                        lateCallbackIgnored.set(true);
                        log.warn("[AI_ATTEMPT_LATE] session={} clientMessageId={} stage=INTERMEDIATE late partial response ignored (chars={})",
                                sessionId, clientMessageId, token != null ? token.length() : 0);
                        return;
                    }
                    if (firstTokenAt.get() == null) {
                        firstTokenAt.set(System.currentTimeMillis());
                    }
                    if (token != null) {
                        partialChars.addAndGet(token.length());
                    }

                    intermediateResponse.append(token);
                    fullResponse.append(token);

                    filterBuffer.append(token);
                    String content = filterBuffer.toString();
                    filterBuffer.setLength(0);

                    while (!content.isEmpty()) {
                        if (insideLlmThink.get()) {
                            int closeIdx = content.indexOf("</think>");
                            if (closeIdx != -1) {
                                insideLlmThink.set(false);
                                content = content.substring(closeIdx + 8);
                            } else {
                                int potentialIdx = getPotentialPrefixIndex(content, "</think>");
                                if (potentialIdx != -1) {
                                    filterBuffer.append(content.substring(potentialIdx));
                                }
                                break;
                            }
                        } else {
                            int openIdx = content.indexOf("<think>");
                            if (openIdx != -1) {
                                String before = content.substring(0, openIdx);
                                if (!before.isEmpty()) {
                                    sseTransport.sendTokenToClient(emitter, before, clientDisconnected, generatingMarked, sessionId, clientMessageId, reasoningModelName);
                                }
                                insideLlmThink.set(true);
                                content = content.substring(openIdx + 7);
                            } else {
                                int potentialOpen = getPotentialPrefixIndex(content, "<think>");
                                int potentialClose = getPotentialPrefixIndex(content, "</think>");
                                int potentialIdx = Math.max(potentialOpen, potentialClose);

                                if (potentialIdx != -1) {
                                    String before = content.substring(0, potentialIdx);
                                    if (!before.isEmpty()) {
                                        sseTransport.sendTokenToClient(emitter, before, clientDisconnected, generatingMarked, sessionId, clientMessageId, reasoningModelName);
                                    }
                                    filterBuffer.append(content.substring(potentialIdx));
                                } else {
                                    sseTransport.sendTokenToClient(emitter, content, clientDisconnected, generatingMarked, sessionId, clientMessageId, reasoningModelName);
                                }
                                break;
                            }
                        }
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    if (!roundFinished.compareAndSet(false, true)) {
                        lateCallbackIgnored.set(true);
                        log.warn("[AI_ATTEMPT_LATE] session={} clientMessageId={} stage=INTERMEDIATE late complete response ignored",
                                sessionId, clientMessageId);
                        return;
                    }
                    timeoutFuture.cancel(false);
                    long elapsedMs = System.currentTimeMillis() - stageStartedAt;
                    Long firstTokenMs = firstTokenAt.get() != null ? (firstTokenAt.get() - stageStartedAt) : null;

                    String cleanText = sessionChatMemoryService.sanitizeAssistantMessage(intermediateResponse.toString());
                    boolean isEmpty = cleanText == null || cleanText.isBlank();
                    IntermediateTerminalCause cause = isEmpty
                            ? IntermediateTerminalCause.INTERMEDIATE_COMPLETED_EMPTY
                            : IntermediateTerminalCause.INTERMEDIATE_COMPLETED_WITH_TEXT;

                    recordAttempt(sessionId, clientMessageId, 1, provider, reasoningModelName, elapsedMs, firstTokenMs,
                            cause, partialChars.get(), false, true, lateCallbackIgnored.get(), null);

                    sessionChatMemoryService.appendAssistantMessage(sessionId, cleanText, systemPrompt);

                    generatingMarked.set(false);
                    sseTransport.safeSend(emitter, "token", Map.of("token", "\n\n<think>\nĐang tiến hành bước tiếp theo...\n</think>\n\n"), MediaType.APPLICATION_JSON);
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.THINKING, modelName, null, null);
                    sseTransport.safeSend(emitter, "phase", Phase.THINKING.name(), null);

                    continuationExecuted.set(true);
                    continueToNextRound.run();
                }

                @Override
                public void onError(Throwable error) {
                    if (!roundFinished.compareAndSet(false, true)) {
                        lateCallbackIgnored.set(true);
                        log.warn("[AI_ATTEMPT_LATE] session={} clientMessageId={} stage=INTERMEDIATE late error response ignored: {}",
                                sessionId, clientMessageId, error != null ? error.getMessage() : "null");
                        return;
                    }
                    timeoutFuture.cancel(false);
                    long elapsedMs = System.currentTimeMillis() - stageStartedAt;
                    Long firstTokenMs = firstTokenAt.get() != null ? (firstTokenAt.get() - stageStartedAt) : null;
                    boolean hadTokens = partialChars.get() > 0 || firstTokenAt.get() != null;
                    IntermediateTerminalCause cause = hadTokens
                            ? IntermediateTerminalCause.INTERMEDIATE_MODEL_ERROR_AFTER_PARTIAL
                            : IntermediateTerminalCause.INTERMEDIATE_MODEL_ERROR_BEFORE_FIRST_TOKEN;

                    recordAttempt(sessionId, clientMessageId, 1, provider, reasoningModelName, elapsedMs, firstTokenMs,
                            cause, partialChars.get(), false, true, lateCallbackIgnored.get(), error);

                    log.error("[Intermediate] Error during intermediate reasoning model streaming for session {}: {}",
                            sessionId, error != null ? error.getMessage() : "null");

                    generatingMarked.set(false);
                    sseTransport.safeSend(emitter, "token", Map.of("token", "\n\n<think>\nĐang tiến hành bước tiếp theo...\n</think>\n\n"), MediaType.APPLICATION_JSON);
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.THINKING, modelName, null, null);
                    sseTransport.safeSend(emitter, "phase", Phase.THINKING.name(), null);

                    continuationExecuted.set(true);
                    continueToNextRound.run();
                }
            });
        } catch (Exception syncEx) {
            timeoutFuture.cancel(false);
            if (roundFinished.compareAndSet(false, true)) {
                long elapsedMs = System.currentTimeMillis() - stageStartedAt;
                IntermediateTerminalCause cause = IntermediateTerminalCause.INTERMEDIATE_SYNC_START_FAILURE;

                recordAttempt(sessionId, clientMessageId, 1, provider, reasoningModelName, elapsedMs, null,
                        cause, partialChars.get(), false, true, lateCallbackIgnored.get(), syncEx);

                log.error("[Intermediate] Synchronous exception during intermediate reasoning model chat for session {}: {}",
                        sessionId, syncEx.getMessage());

                generatingMarked.set(false);
                sseTransport.safeSend(emitter, "token", Map.of("token", "\n\n<think>\nĐang tiến hành bước tiếp theo...\n</think>\n\n"), MediaType.APPLICATION_JSON);
                chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.THINKING, modelName, null, null);
                sseTransport.safeSend(emitter, "phase", Phase.THINKING.name(), null);

                continuationExecuted.set(true);
                continueToNextRound.run();
            }
        }
    }

    private void recordAttempt(
            Long sessionId,
            String clientMessageId,
            int attempt,
            String provider,
            String modelName,
            long elapsedMs,
            Long firstTokenMs,
            IntermediateTerminalCause cause,
            int partialChars,
            boolean watchdogWon,
            boolean continuationExecuted,
            boolean lateCallbackIgnored,
            Throwable error) {

        log.info("[AI_ATTEMPT] session={} clientMessageId={} stage=INTERMEDIATE attempt={} provider={} model={} mode=TEXT elapsedMs={} firstTokenMs={} terminalCause={} partialChars={} watchdogWon={} continuationExecuted={} lateCallbackIgnored={}",
                sessionId, clientMessageId, attempt, provider, modelName, elapsedMs,
                firstTokenMs != null ? firstTokenMs : "none",
                cause, partialChars, watchdogWon, continuationExecuted, lateCallbackIgnored);

        Consumer<IntermediateDiagnosticOutcome> listener = this.diagnosticOutcomeListener;
        if (listener != null) {
            listener.accept(new IntermediateDiagnosticOutcome(
                    sessionId, clientMessageId, "INTERMEDIATE", attempt, provider, modelName, "TEXT",
                    elapsedMs, firstTokenMs, cause, partialChars, watchdogWon, continuationExecuted,
                    lateCallbackIgnored, error));
        }
    }

    private int getPotentialPrefixIndex(String content, String target) {
        for (int i = 1; i < target.length(); i++) {
            String prefix = target.substring(0, i);
            if (content.endsWith(prefix)) {
                return content.length() - prefix.length();
            }
        }
        return -1;
    }
}
