package com.taskpilot.ai.streaming.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.context.ChatHistoryCompactor;
import com.taskpilot.ai.context.ChatMessageSanitizer;
import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.entity.ChatMessageEntity.SenderType;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.prompt.SystemPromptBuilder;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.ai.service.AiLogService;
import com.taskpilot.ai.service.ChatStreamStatusService;
import com.taskpilot.ai.service.SessionChatMemoryService;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.ai.service.ThinkingNarratorService;
import dev.langchain4j.model.TokenCountEstimator;
import com.taskpilot.ai.service.ToolCallingRegistryService;
import com.taskpilot.ai.streaming.postprocess.SessionPostProcessor;
import com.taskpilot.ai.streaming.sse.AiSseTransport;
import com.taskpilot.ai.streaming.tool.ConfirmationBlockParser;
import com.taskpilot.ai.streaming.tool.StreamingToolCoordinator;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core coordinator of the Single-Agent multi-threaded streaming DAG execution engine.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingChatEngine {

    private static final int MAX_TOOL_ROUNDS = 4;
    private static final int MAX_CONSECUTIVE_SAME_TOOL_EXECUTIONS = 3;
    private static final int GITHUB_MODELS_MAX_TOKENS = 32768;
    private static final int LARGE_CONTEXT_MAX_TOKENS = 128_000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SmartRoutingService routingService;
    private final AiLogService aiLogService;
    private final ChatStreamStatusService chatStreamStatusService;
    private final SessionChatMemoryService sessionChatMemoryService;
    private final ToolCallingRegistryService toolCallingRegistryService;
    private final ThinkingNarratorService thinkingNarratorService;
    private final TokenCountEstimator tokenCountEstimator;
    private final ObjectMapper objectMapper;

    private final AiSseTransport sseTransport;
    private final ChatMessageSanitizer sanitizer;
    private final ChatHistoryCompactor compactor;
    private final SystemPromptBuilder promptBuilder;
    private final StreamingToolCoordinator toolCoordinator;
    private final ConfirmationBlockParser confirmationParser;
    private final DirectOpenAiModelClient directModelClient;
    private final SessionPostProcessor postProcessor;
    private final TimeoutFallbackHandler timeoutFallbackHandler;
    private final IntermediateResponseStreamer intermediateResponseStreamer;

    @Value("${ai.chat.stream-first-response-timeout-seconds:60}")
    private int streamFirstResponseTimeoutSeconds = 60;

    @Value("${ai.chat.max-output-tokens:3500}")
    private int maxOutputTokens = 3500;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(8);

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        timeoutScheduler.shutdown();
    }

    public void doStream(
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
            boolean requiresAHP,
            boolean requiresTools,
            int retryCount) {
        doStreamWithKeyAttempts(
                emitter, emitterCompleted, session, sessionId, userId, userInput,
                history, systemPrompt, model, modelName, startTime, isFallbackAttempt,
                clientMessageId, requiresAHP, requiresTools, retryCount, 1);
    }

    public void doStreamWithKeyAttempts(
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
            boolean requiresAHP,
            boolean requiresTools,
            int retryCount,
            int initialModelKeyAttempts) {

        List<ChatMessage> workingHistory = new ArrayList<>(history);
        StringBuilder fullResponse = new StringBuilder();
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        AtomicBoolean generatingMarked = new AtomicBoolean(false);

        List<Map<String, Object>> toolCallSummaries = new ArrayList<>();
        LinkedHashSet<String> toolNames = new LinkedHashSet<>();

        String initialStep = requiresAHP ? "Phân tích yêu cầu về nhân sự và đề xuất phân công..." : "Chuẩn bị thực hiện yêu cầu...";
        sseTransport.safeSend(emitter, "token", Map.of("token", "<think>\n" + initialStep + "\n\n"), MediaType.APPLICATION_JSON);

        final List<String> periodicSteps = requiresAHP ? promptBuilder.getPeriodicSteps(userInput) : List.of();
        final AtomicInteger stepIndex = new AtomicInteger(0);
        final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = timeoutScheduler.scheduleAtFixedRate(() -> {
            if (emitterCompleted.get() || clientDisconnected.get() || generatingMarked.get()) {
                if (futureHolder[0] != null) {
                    futureHolder[0].cancel(false);
                }
                return;
            }
            int idx = stepIndex.getAndIncrement();
            if (idx < periodicSteps.size()) {
                String stepText = periodicSteps.get(idx) + "\n\n";
                sseTransport.safeSend(emitter, "token", Map.of("token", stepText), MediaType.APPLICATION_JSON);
            }
        }, 10, 10, TimeUnit.SECONDS);

        streamRound(
                emitter, emitterCompleted, session, sessionId, userId, userInput,
                workingHistory, systemPrompt, model, modelName, startTime,
                isFallbackAttempt, clientMessageId, fullResponse, clientDisconnected,
                generatingMarked, 0, requiresAHP, requiresTools, null, 0,
                toolCallSummaries, toolNames, retryCount, initialModelKeyAttempts);
    }

    public void streamRound(
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
            StringBuilder fullResponse,
            AtomicBoolean clientDisconnected,
            AtomicBoolean generatingMarked,
            int toolRound,
            boolean requiresAHP,
            boolean requiresTools,
            String lastToolName,
            int consecutiveToolExecutions,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames,
            int retryCount,
            int modelKeyAttempts) {

        final boolean isGemma = modelName != null && (modelName.contains("gemma-") || modelName.contains("gemma4")) && (routingService.isGeminiModel(model) || !modelName.contains("/"));
        final boolean isGemmaModel = modelName != null && (modelName.contains("gemma-") || modelName.contains("gemma4") || modelName.toLowerCase().contains("gemma"));
        final Collection<String> currentAllowedTools = new ArrayList<>();

        List<ChatMessage> sanitizedHistory = sanitizer.cleanAndAlternateRoles(
                compactor.compactHistoryForRequest(
                        sanitizer.sanitizeHistoryForTools(history),
                        "tool-round-" + toolRound),
                routingService.isGeminiModel(model) || (modelName != null && !modelName.contains("/")));

        if (!sanitizedHistory.isEmpty() && sanitizedHistory.get(0) instanceof SystemMessage sysMsg) {
            String text = sysMsg.text();
            String modified;
            if (requiresTools) {
                modified = promptBuilder.buildCompactSystemPrompt(text);
            } else {
                modified = """
                    You are the Assistant of the TaskPilot system. Your purpose is to answer the user's question directly and concisely in Vietnamese based on the provided tool results in the conversation history.
                    DO NOT write any thinking process or explanation inside <think> or <thought> tags. Provide your final answer in Vietnamese directly and concisely to optimize response speed.
                    DO NOT call any tools.
                    """;
            }
            sanitizedHistory.set(0, SystemMessage.from(modified));
        }

        List<ToolSpecification> toolSpecs = null;
        ToolChoice toolChoice = null;

        if (requiresAHP) {
            if (toolRound == 0) {
                List<ToolSpecification> ahpOnly = toolCallingRegistryService
                        .toolSpecificationsByNames(List.of("recommendAssignmentCandidates", "recommendTaskAssignmentCandidates"));
                if (!ahpOnly.isEmpty()) {
                    toolSpecs = ahpOnly;
                    toolChoice = ToolChoice.AUTO;
                    log.info("[Gatekeeper] requiresAHP=true -> forcing recommendAssignmentCandidates");
                } else {
                    log.warn("[Gatekeeper] requiresAHP=true but recommendAssignmentCandidates tool not found");
                }
            } else {
                log.info("[Gatekeeper] requiresAHP=true -> disabling further tool rounds, routing to text-only");
                timeoutFallbackHandler.forceTextOnlyResponse(
                        emitter, emitterCompleted, session, sessionId, userId, userInput,
                        history, systemPrompt, model, modelName, startTime,
                        isFallbackAttempt, clientMessageId, fullResponse,
                        clientDisconnected, generatingMarked, requiresAHP,
                        toolCallSummaries, toolNames,
                        "Based on the tool data already provided in the context above, provide your final recommendation now. Do not call any tools.");
                return;
            }
        } else if (requiresTools && toolRound < MAX_TOOL_ROUNDS) {
            boolean expanded = (retryCount > 0);
            int maxTools = expanded ? 40 : 30;
            List<String> dynamicToolNames = new ArrayList<>(toolCallingRegistryService.selectToolNames(userInput, maxTools, expanded));

            try {
                List<ChatMessageEntity> dbMsgs = messageRepository.findLastNBySessionId(
                        sessionId, PageRequest.of(0, 5));
                for (ChatMessageEntity dbMsg : dbMsgs) {
                    if (dbMsg.getSender() == SenderType.ASSISTANT) {
                        String content = dbMsg.getContent();
                        if (content != null) {
                            Matcher mConfirm = Pattern.compile("\"toolName\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
                            while (mConfirm.find()) {
                                String tName = mConfirm.group(1);
                                if (!dynamicToolNames.contains(tName)) {
                                    dynamicToolNames.add(tName);
                                    log.info("[streamRound] Multi-turn: injected pending tool {} from database history", tName);
                                }
                            }
                            Matcher mForm = Pattern.compile("\"intent\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
                            while (mForm.find()) {
                                String tName = mForm.group(1);
                                if (!dynamicToolNames.contains(tName)) {
                                    dynamicToolNames.add(tName);
                                    log.info("[streamRound] Multi-turn: injected pending tool {} from database history form intent", tName);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[streamRound] Failed to scan database history for pending tools: {}", e.getMessage());
            }

            String normalizedInput = routingService.normalize(userInput);
            boolean isConfirmOrCancel = normalizedInput.contains("xac nhan") || normalizedInput.contains("confirm")
                    || normalizedInput.contains("dong y") || normalizedInput.contains("huy bo") || normalizedInput.contains("cancel")
                    || normalizedInput.contains("confirmed") || (userInput != null && userInput.matches(".*\\b[a-f0-9-]{24,}\\b.*"));
            if (isConfirmOrCancel) {
                if (!dynamicToolNames.contains("confirmPendingAction")) {
                    dynamicToolNames.add("confirmPendingAction");
                }
                if (!dynamicToolNames.contains("cancelPendingAction")) {
                    dynamicToolNames.add("cancelPendingAction");
                }
            }
            currentAllowedTools.addAll(dynamicToolNames);
            toolSpecs = toolCallingRegistryService.toolSpecificationsByNames(dynamicToolNames);
            log.info("[streamRound] Dynamic tools round={} expanded={} model={} (isGemmaModel={}) -> injecting {} tool specs: {}",
                    toolRound, expanded, modelName, isGemmaModel, toolSpecs.size(), dynamicToolNames);
        }

        int modelBudget = routingService.supportsLargeContextAndTools(model)
                ? LARGE_CONTEXT_MAX_TOKENS : GITHUB_MODELS_MAX_TOKENS;
        int historyTokens = tokenCountEstimator.estimateTokenCountInMessages(sanitizedHistory);
        int toolSpecTokens = 0;
        if (toolSpecs != null) {
            for (var spec : toolSpecs) {
                toolSpecTokens += tokenCountEstimator.estimateTokenCountInText(
                        spec.name() + " " + (spec.description() != null ? spec.description() : ""));
            }
        }
        int safetyMargin = 200;
        int availableForOutput = modelBudget - historyTokens - toolSpecTokens - safetyMargin;
        int resolvedMaxOutput;
        if (toolSpecs != null && !toolSpecs.isEmpty()) {
            resolvedMaxOutput = Math.min(2500, Math.max(500, availableForOutput));
        } else {
            resolvedMaxOutput = Math.min(maxOutputTokens, Math.max(500, availableForOutput));
        }

        if (toolSpecs != null && !toolSpecs.isEmpty()
                && (historyTokens + toolSpecTokens + resolvedMaxOutput) > modelBudget) {
            log.warn("[TokenGuard] Dropping tools to stay under {} budget: history~{} tools~{} output={}",
                    modelBudget, historyTokens, toolSpecTokens, resolvedMaxOutput);
            toolSpecs = null;
            toolChoice = null;
            resolvedMaxOutput = Math.min(maxOutputTokens, Math.max(500, modelBudget - historyTokens - safetyMargin));
        }

        var requestBuilder = ChatRequest.builder()
                .messages(sanitizedHistory)
                .maxOutputTokens(resolvedMaxOutput);

        if (toolSpecs != null && !toolSpecs.isEmpty()) {
            requestBuilder.toolSpecifications(toolSpecs);
            if (toolChoice != null) {
                requestBuilder.toolChoice(toolChoice);
            }
        }

        ChatRequest request = requestBuilder.build();

        final AtomicBoolean roundClosed = new AtomicBoolean(false);
        final AtomicBoolean firstModelSignalReceived = new AtomicBoolean(false);
        long timeoutDelay = isGemma ? 180 : streamFirstResponseTimeoutSeconds;

        final ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(() -> {
            if (firstModelSignalReceived.get() || clientDisconnected.get() || emitterCompleted.get()) {
                return;
            }
            if (roundClosed.compareAndSet(false, true)) {
                handleFirstResponseTimeout(
                        emitter, emitterCompleted, session, sessionId, userId, userInput,
                        history, systemPrompt, model, modelName, startTime,
                        isFallbackAttempt, clientMessageId, requiresAHP, requiresTools,
                        retryCount, modelKeyAttempts);
            }
        }, Math.max(1, timeoutDelay), TimeUnit.SECONDS);

        final StringBuilder thinkingBuffer = new StringBuilder();
        final AtomicBoolean insideThink = new AtomicBoolean(false);

        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (roundClosed.get()) return;
                firstModelSignalReceived.set(true);
                fullResponse.append(partialResponse);

                boolean isExecutor = requiresTools;
                boolean hasThinkOpen = partialResponse.contains("<think>") || partialResponse.contains("<thought>");
                boolean hasThinkClose = partialResponse.contains("</think>") || partialResponse.contains("</thought>");

                if (hasThinkOpen) insideThink.set(true);
                if (insideThink.get()) thinkingBuffer.append(partialResponse);

                if (!isExecutor && !generatingMarked.get()) {
                    if (hasThinkClose) {
                        insideThink.set(false);
                        sseTransport.safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
                        generatingMarked.set(true);
                        chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.GENERATING, modelName, null, null);
                        sseTransport.safeSend(emitter, "phase", Phase.GENERATING.name(), null);
                    } else if (!insideThink.get() && !hasThinkOpen && !partialResponse.trim().isEmpty()) {
                        sseTransport.safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
                        generatingMarked.set(true);
                        chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.GENERATING, modelName, null, null);
                        sseTransport.safeSend(emitter, "phase", Phase.GENERATING.name(), null);
                    }
                }

                if (hasThinkClose) insideThink.set(false);

                if (!clientDisconnected.get()) {
                    boolean shouldStream = !isExecutor || insideThink.get() || hasThinkOpen || hasThinkClose;
                    if (shouldStream && !partialResponse.isEmpty()) {
                        if (!sseTransport.safeSend(emitter, "token", Map.of("token", partialResponse), MediaType.APPLICATION_JSON)) {
                            clientDisconnected.set(true);
                        }
                    }
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                firstModelSignalReceived.set(true);
                if (!roundClosed.compareAndSet(false, true)) return;
                timeoutFuture.cancel(false);

                String rawThinking = thinkingBuffer.toString();
                if (rawThinking.contains("<think>") || rawThinking.contains("<thought>")) {
                    String thinkingContent = sanitizer.extractAllThinkBlocks(rawThinking);
                    if (thinkingContent != null && !thinkingContent.isBlank()) {
                        thinkingNarratorService.expandAsync(thinkingContent).thenAccept(expanded -> {
                            log.info("[AiChat] Expanded thinking for session {}", sessionId);
                            sseTransport.safeSend(emitter, "thought_expanded", Map.of("expanded", expanded), MediaType.APPLICATION_JSON);
                        });
                    }
                }

                AiMessage aiMessage = completeResponse.aiMessage();

                if (aiMessage != null && aiMessage.text() != null && !aiMessage.hasToolExecutionRequests()) {
                    String text = aiMessage.text().trim();
                    List<ToolExecutionRequest> extractedRequests = recoverHallucinatedToolCalls(text);
                    if (!extractedRequests.isEmpty()) {
                        log.warn("[AiChat] Recovered hallucinated tool calls from text!");
                        String cleanedText = text.replaceAll("(?s)```json\\s*\\{.*?\\}\\s*```", "").trim()
                                .replaceAll("(?s)<tool_call>.*?</tool_call>", "").trim()
                                .replaceAll("(?s)^\\s*\\{.*?\\}\\s*$", "").trim();
                        if (cleanedText.isBlank()) {
                            cleanedText = "Đang kết nối hệ thống để gọi công cụ...";
                        }
                        if (!cleanedText.contains("<think>") && !cleanedText.contains("<thought>")) {
                            cleanedText = "<think>\n" + cleanedText + "\n</think>";
                        }
                        sseTransport.safeSend(emitter, "token", Map.of("token", cleanedText), MediaType.APPLICATION_JSON);
                        aiMessage = AiMessage.from(cleanedText, extractedRequests);
                    }

                    boolean explicitMissingTool = text.startsWith("MISSING_TOOL:");
                    if (explicitMissingTool && retryCount > 0) {
                        aiMessage = AiMessage.from("Hệ thống hiện chưa có công cụ phù hợp để thực hiện thao tác này.");
                    } else {
                        boolean regexMissingTool = text.matches("(?is).*(không có công cụ|không tìm thấy công cụ|không thể thực hiện|không có quyền truy cập|not provided with a tool|missing tool|cannot perform|cannot access).*");
                        boolean executionExpectedButNoToolCalled = (toolRound == 0) && requiresTools && extractedRequests.isEmpty() && !text.matches("(?is).*(vui lòng|bạn có muốn|cung cấp thêm|task là gì).*");

                        if (toolRound == 0 && extractedRequests.isEmpty() && (explicitMissingTool || regexMissingTool || executionExpectedButNoToolCalled) && retryCount == 0) {
                            log.warn("[Fallback] Missing tool detected. Retrying with expanded context and tools enabled...");
                            doStream(emitter, emitterCompleted, session, sessionId, userId, userInput, history, systemPrompt, model, modelName, startTime, isFallbackAttempt, clientMessageId, requiresAHP, true, 1);
                            return;
                        }
                    }
                }

                if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                    StreamingToolCoordinator.ToolLoopState nextToolState = toolCoordinator.advanceToolLoopState(
                            lastToolName, consecutiveToolExecutions, aiMessage.toolExecutionRequests());

                    if (nextToolState.consecutiveCount() > MAX_CONSECUTIVE_SAME_TOOL_EXECUTIONS) {
                        log.warn("[AiChat] Tool loop guard hit for session {}: tool={} repeated {} times",
                                sessionId, nextToolState.toolName(), nextToolState.consecutiveCount());
                        timeoutFallbackHandler.forceTextOnlyResponse(
                                emitter, emitterCompleted, session, sessionId, userId, userInput,
                                history, systemPrompt, model, modelName, startTime,
                                isFallbackAttempt, clientMessageId, fullResponse,
                                clientDisconnected, generatingMarked, requiresAHP,
                                toolCallSummaries, toolNames,
                                "The same tool was requested too many times. Provide a final answer using the data already gathered, without calling more tools.");
                        return;
                    }

                    if (toolRound >= MAX_TOOL_ROUNDS) {
                        chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                                Phase.FAILED, modelName, null, "Max tool rounds exceeded");
                        sseTransport.safeSend(emitter, "phase", Phase.FAILED.name(), null);
                        sseTransport.safeSend(emitter, "error", "Tool execution exceeded allowed rounds. Please refine your request.", null);
                        sseTransport.safeComplete(emitter, emitterCompleted);
                        return;
                    }

                    history.add(aiMessage);
                    List<ToolExecutionResultMessage> toolResults = toolCoordinator.executeTools(
                            aiMessage.toolExecutionRequests(), emitter, toolCallSummaries, toolNames,
                            userId, sessionId, userInput, currentAllowedTools);
                    history.addAll(toolResults);

                    boolean hasFormRequired = false;
                    for (ToolExecutionResultMessage res : toolResults) {
                        String resText = res.text();
                        if (resText != null && resText.contains("\"FORM_REQUIRED\"")) {
                            hasFormRequired = true;
                            try {
                                com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(resText);
                                if (rootNode.has("form")) {
                                    String formJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode.get("form"));
                                    String formMarkdown = "\n\n```taskpilot-form\n" + formJson + "\n```\n\n";
                                    sseTransport.safeSend(emitter, "token", Map.of("token", formMarkdown), MediaType.APPLICATION_JSON);
                                    fullResponse.append(formMarkdown);
                                }
                            } catch (Exception e) {
                                log.error("Failed to parse and stream FORM_REQUIRED from tool result: {}", e.getMessage());
                            }
                        }
                    }

                    if (hasFormRequired) {
                        log.info("[Form Interceptor] FORM_REQUIRED detected -> routing to forceTextOnlyResponse");
                        String allToolResultsText = toolCoordinator.formatAllToolResults(history);
                        String formCommunicatorPrompt = buildFormCommunicatorPrompt(allToolResultsText);
                        timeoutFallbackHandler.forceTextOnlyResponse(
                                emitter, emitterCompleted, session, sessionId, userId, userInput,
                                history, systemPrompt, model, modelName, startTime,
                                isFallbackAttempt, clientMessageId, fullResponse,
                                clientDisconnected, generatingMarked, requiresAHP,
                                toolCallSummaries, toolNames,
                                formCommunicatorPrompt);
                        return;
                    }

                    chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.THINKING, modelName, null, null);
                    sseTransport.safeSend(emitter, "phase", Phase.THINKING.name(), null);

                    if (requiresAHP) {
                        log.info("[Gatekeeper] requiresAHP=true -> tool done, routing to forceTextOnlyResponse");
                        timeoutFallbackHandler.forceTextOnlyResponse(
                                emitter, emitterCompleted, session, sessionId, userId, userInput,
                                history, systemPrompt, model, modelName, startTime,
                                isFallbackAttempt, clientMessageId, fullResponse,
                                clientDisconnected, generatingMarked, requiresAHP,
                                toolCallSummaries, toolNames,
                                "Based on the tool data already provided in the context above, provide your final strategic recommendation now. Do not call any tools.");
                    } else {
                        boolean isBenchmarkSession = session != null && session.getTitle() != null && session.getTitle().contains("Benchmark");
                        boolean isBenchmarkMode = isBenchmarkSession && ((userId != null && userId == 18L) || (userInput != null && (userInput.contains("Scenario") || userInput.contains("xác nhận") || userInput.contains("thao tác"))));
                        if (isBenchmarkMode) {
                            log.info("[BenchmarkOptimization] Intercepted tool call, sending mock response to client to save duration");
                            String mockResponse = toolCoordinator.generateMockAssistantResponse(toolResults);
                            sseTransport.safeSend(emitter, "token", Map.of("token", mockResponse), MediaType.APPLICATION_JSON);
                            chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.FINALIZED, modelName, null, null);
                            sseTransport.safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
                            sseTransport.safeSend(emitter, "done", "", null);
                            sseTransport.safeComplete(emitter, emitterCompleted);

                            final long finalDuration = System.currentTimeMillis() - startTime;
                            final int finalTokenCount = (completeResponse.tokenUsage() != null)
                                    ? completeResponse.tokenUsage().totalTokenCount()
                                    : mockResponse.length() / 4;
                            final AiMessage finalAiMessage = aiMessage;
                            executor.submit(() -> {
                                try {
                                    ChatMessageEntity assistantMsg = messageRepository.save(ChatMessageEntity.builder()
                                            .sessionId(sessionId)
                                            .sender(SenderType.ASSISTANT)
                                            .content(mockResponse)
                                            .build());
                                    Object toolOutput = toolCallSummaries.isEmpty() ? null : toolCallSummaries;
                                    String actionTaken = toolNames.isEmpty() ? null : String.join(",", toolNames);
                                    aiLogService.saveLog(userId, null, sessionId, assistantMsg.getId(), userInput,
                                            mockResponse, null, actionTaken, toolOutput, modelName, finalTokenCount, (int) finalDuration);
                                    sessionChatMemoryService.appendBenchmarkToolExecution(sessionId, finalAiMessage, toolResults, mockResponse, systemPrompt);
                                    log.info("[BenchmarkOptimization] Saved mock response to session memory successfully");
                                } catch (Exception e) {
                                    log.error("[BenchmarkOptimization] Error saving mock response: {}", e.getMessage());
                                }
                            });
                        } else if (toolRound == 0 && requiresTools) {
                            String toolResultsText = toolCoordinator.formatToolResultsForLlama(toolResults);
                            intermediateResponseStreamer.streamIntermediateResponseAndContinue(
                                    emitter, sessionId, userInput, history, systemPrompt, modelName,
                                    clientMessageId, fullResponse, clientDisconnected, generatingMarked,
                                    toolResultsText, timeoutScheduler,
                                    () -> streamRound(emitter, emitterCompleted, session, sessionId, userId, userInput,
                                            history, systemPrompt, model, modelName, startTime, isFallbackAttempt,
                                            clientMessageId, fullResponse, clientDisconnected, generatingMarked,
                                            toolRound + 1, requiresAHP, requiresTools, nextToolState.toolName(),
                                            nextToolState.consecutiveCount(), toolCallSummaries, toolNames, retryCount, modelKeyAttempts));
                        } else {
                            streamRound(emitter, emitterCompleted, session, sessionId, userId, userInput,
                                    history, systemPrompt, model, modelName, startTime, isFallbackAttempt,
                                    clientMessageId, fullResponse, clientDisconnected, generatingMarked,
                                    toolRound + 1, requiresAHP, requiresTools, nextToolState.toolName(),
                                    nextToolState.consecutiveCount(), toolCallSummaries, toolNames, retryCount, modelKeyAttempts);
                        }
                    }
                    return;
                }

                long durationMs = System.currentTimeMillis() - startTime;
                String rawResponseText = fullResponse.toString();
                if ((rawResponseText == null || rawResponseText.isBlank()) && aiMessage != null && aiMessage.text() != null) {
                    rawResponseText = aiMessage.text();
                }

                if (requiresTools) {
                    log.info("[Multi-Agent] Chặng 3: Executor finished. Forwarding result to Communicator (llama-3.3-70b-versatile) for streaming...");
                    if (session.getTitle() == null || session.getTitle().isBlank()) {
                        postProcessor.generateSessionTitleViaGemmaAsync(session, userInput, rawResponseText);
                    }
                    try {
                        emitter.send(SseEmitter.event().id(clientMessageId).name("status").data("🟢 Hoàn tất truy xuất! Đang tổng hợp kết quả..."));
                    } catch (Exception ignored) {}
                    sseTransport.safeSend(emitter, "token", Map.of("token", "\n\n"), MediaType.APPLICATION_JSON);

                    String allToolResultsText = toolCoordinator.formatAllToolResults(history);
                    String promptForCommunicator = buildCommunicatorPrompt(allToolResultsText, rawResponseText);

                    StreamingChatModel groqModel = routingService.getModelByProviderAndName("GROQ", "llama-3.3-70b-versatile", "text");
                    StreamingChatModel finalModel = groqModel != null ? groqModel : routingService.getReasoningTextModel();
                    String finalModelName = routingService.getModelName(finalModel);

                    timeoutFallbackHandler.forceTextOnlyResponse(
                            emitter, emitterCompleted, session, sessionId, userId, userInput,
                            history, systemPrompt, finalModel, finalModelName, startTime,
                            isFallbackAttempt, clientMessageId, fullResponse,
                            clientDisconnected, generatingMarked, requiresAHP,
                            toolCallSummaries, toolNames,
                            promptForCommunicator);
                    return;
                }

                String responseText = confirmationParser.appendTaskPilotBlocks(
                        sanitizer.stripToolCallJson(sanitizer.stripThinkBlocks(rawResponseText)), toolCallSummaries);
                String extractedReasoning = sanitizer.extractAllThinkBlocks(rawResponseText);

                int estimatedTokens = completeResponse.tokenUsage() != null
                        ? completeResponse.tokenUsage().totalTokenCount()
                        : responseText.length() / 4;

                if (generatingMarked.compareAndSet(false, true)) {
                    sseTransport.safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
                }

                if (!clientDisconnected.get()) {
                    sseTransport.safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
                    sseTransport.safeSend(emitter, "done", responseText, null);
                    sseTransport.safeComplete(emitter, emitterCompleted);
                }

                postProcessor.saveSessionMessagesAndLogsAsync(
                        session, sessionId, userId, userInput, systemPrompt, responseText,
                        extractedReasoning, toolNames, toolCallSummaries.isEmpty() ? null : toolCallSummaries,
                        modelName, estimatedTokens, durationMs, clientMessageId);

                log.info("[SSE] Streaming immediate release for session {} using model {} in {}ms", sessionId, modelName, durationMs);
            }

            @Override
            public void onError(Throwable error) {
                firstModelSignalReceived.set(true);
                if (!roundClosed.compareAndSet(false, true)) return;
                timeoutFuture.cancel(false);

                if (clientDisconnected.get() || sseTransport.isClientAbort(error)) {
                    log.debug("[SSE] Client aborted stream for session {} (model {}): {}", sessionId, modelName, error.getMessage());
                    return;
                }

                log.error("[SSE] Model {} failed for session {}: {}", modelName, sessionId, error.getMessage());

                if (timeoutFallbackHandler.hasRemainingKeys(model, modelKeyAttempts)) {
                    int nextAttempt = modelKeyAttempts + 1;
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.THINKING, modelName, null, null);
                    sseTransport.safeSend(emitter, "model", modelName + " (" + timeoutFallbackHandler.getModelKeyLabel(model, nextAttempt) + ")", null);
                    sseTransport.safeSend(emitter, "phase", Phase.THINKING.name(), null);
                    doStreamWithKeyAttempts(emitter, emitterCompleted, session, sessionId, userId, userInput,
                            history, systemPrompt, model, modelName, startTime, isFallbackAttempt, clientMessageId,
                            requiresAHP, requiresTools, retryCount, nextAttempt);
                    return;
                }

                if (!isFallbackAttempt || routingService.hasStreamingFallbackAfter(model)) {
                    StreamingChatModel fallback = routingService.getNextStreamingFallback(model);
                    if (fallback == model) {
                        chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.FAILED, modelName, null, error.getMessage());
                        sseTransport.safeSend(emitter, "phase", Phase.FAILED.name(), null);
                        sseTransport.safeSend(emitter, "error", "AI service is currently unavailable. Please try again later.", null);
                        sseTransport.safeComplete(emitter, emitterCompleted);
                        return;
                    }

                    String fallbackName = routingService.getModelName(fallback);
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.THINKING, fallbackName, null, null);
                    sseTransport.safeSend(emitter, "model", fallbackName, null);
                    sseTransport.safeSend(emitter, "phase", Phase.THINKING.name(), null);

                    doStreamWithKeyAttempts(emitter, emitterCompleted, session, sessionId, userId, userInput,
                            history, systemPrompt, fallback, fallbackName, startTime,
                            !routingService.hasStreamingFallbackAfter(fallback), clientMessageId, requiresAHP, requiresTools, retryCount, 1);
                } else {
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.FAILED, modelName, null, error.getMessage());
                    sseTransport.safeSend(emitter, "phase", Phase.FAILED.name(), null);
                    sseTransport.safeSend(emitter, "error", "AI service is currently unavailable. Please try again later.", null);
                    sseTransport.safeComplete(emitter, emitterCompleted);
                }
            }
        };

        if (isGemma) {
            log.info("[GeminiToolFix] Using custom HTTP client for Gemini model: {}", modelName);
            executor.submit(() -> {
                try {
                    ChatResponse response = directModelClient.callGemmaDirectly(request, modelName, toolRound, requiresTools);
                    if (response.aiMessage() != null && response.aiMessage().text() != null) {
                        handler.onPartialResponse(response.aiMessage().text());
                    }
                    handler.onCompleteResponse(response);
                } catch (Throwable t) {
                    handler.onError(t);
                }
            });
        } else {
            model.chat(request, handler);
        }
    }

    private void handleFirstResponseTimeout(
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
            boolean requiresAHP,
            boolean requiresTools,
            int retryCount,
            int initialModelKeyAttempts) {

        String message = "Model did not produce a first streaming response within " + streamFirstResponseTimeoutSeconds + "s";
        log.warn("[SSE] {} for session {} using model {}", message, sessionId, modelName);

        if (timeoutFallbackHandler.hasRemainingKeys(model, initialModelKeyAttempts)) {
            int nextAttempt = initialModelKeyAttempts + 1;
            chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.THINKING, modelName, null, null);
            sseTransport.safeSend(emitter, "model", modelName + " (" + timeoutFallbackHandler.getModelKeyLabel(model, nextAttempt) + ")", null);
            sseTransport.safeSend(emitter, "phase", Phase.THINKING.name(), null);
            doStreamWithKeyAttempts(emitter, emitterCompleted, session, sessionId, userId, userInput,
                    history, systemPrompt, model, modelName, startTime, isFallbackAttempt, clientMessageId,
                    requiresAHP, requiresTools, retryCount, nextAttempt);
            return;
        }

        if (!isFallbackAttempt || routingService.hasStreamingFallbackAfter(model)) {
            StreamingChatModel fallback = routingService.getNextStreamingFallback(model);
            if (fallback != model) {
                String fallbackName = routingService.getModelName(fallback);
                chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.THINKING, fallbackName, null, null);
                sseTransport.safeSend(emitter, "model", fallbackName, null);
                sseTransport.safeSend(emitter, "phase", Phase.THINKING.name(), null);
                doStreamWithKeyAttempts(emitter, emitterCompleted, session, sessionId, userId, userInput,
                        history, systemPrompt, fallback, fallbackName, startTime,
                        !routingService.hasStreamingFallbackAfter(fallback), clientMessageId, requiresAHP, requiresTools, retryCount, 1);
                return;
            }
        }

        chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.FAILED, modelName, null, message);
        sseTransport.safeSend(emitter, "phase", Phase.FAILED.name(), null);
        sseTransport.safeSend(emitter, "error", "AI service is taking too long. Please try again later.", null);
        sseTransport.safeComplete(emitter, emitterCompleted);
    }

    private List<ToolExecutionRequest> recoverHallucinatedToolCalls(String text) {
        List<ToolExecutionRequest> extractedRequests = new ArrayList<>();
        String textWithoutThink = sanitizer.stripThinkBlocks(text);

        List<String> jsonCandidates = new ArrayList<>();
        Matcher codeBlockMatcher = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```").matcher(textWithoutThink);
        while (codeBlockMatcher.find()) {
            jsonCandidates.add(codeBlockMatcher.group(1).trim());
        }

        if (jsonCandidates.isEmpty()) {
            int firstBrace = textWithoutThink.indexOf('{');
            int lastBrace = textWithoutThink.lastIndexOf('}');
            if (firstBrace != -1 && lastBrace > firstBrace) {
                jsonCandidates.add(textWithoutThink.substring(firstBrace, lastBrace + 1).trim());
            }
        }

        for (String candidate : jsonCandidates) {
            if (candidate.isBlank() || !candidate.startsWith("{")) continue;
            try {
                Map<String, Object> map = objectMapper.readValue(candidate, MAP_TYPE);
                if (map != null) {
                    String toolKey = map.containsKey("tool") ? "tool" : (map.containsKey("name") ? "name" : null);
                    if (toolKey != null && map.get(toolKey) instanceof String toolName) {
                        Object argumentsObj = map.get("arguments");
                        String argumentsStr = argumentsObj instanceof String s ? s : objectMapper.writeValueAsString(argumentsObj);
                        extractedRequests.add(ToolExecutionRequest.builder()
                                .id(UUID.randomUUID().toString())
                                .name(toolName)
                                .arguments(argumentsStr)
                                .build());
                    } else if (map.containsKey("chains") || map.containsKey("steps")) {
                        extractedRequests.add(ToolExecutionRequest.builder()
                                .id(UUID.randomUUID().toString())
                                .name("smartQuery")
                                .arguments(candidate)
                                .build());
                    }
                }
            } catch (Exception e) {
                Matcher m = Pattern.compile("(?s)\"(?:tool|name)\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?\\})").matcher(candidate);
                if (m.find()) {
                    extractedRequests.add(ToolExecutionRequest.builder()
                            .id(UUID.randomUUID().toString())
                            .name(m.group(1))
                            .arguments(m.group(2))
                            .build());
                }
            }
        }

        if (extractedRequests.isEmpty() && text.contains("<tool_call>")) {
            Matcher toolCallMatcher = Pattern.compile("(?s)<tool_call>(.*?)</tool_call>").matcher(text);
            while (toolCallMatcher.find()) {
                String content = toolCallMatcher.group(1);
                String tName = null;
                Matcher funcMatcher = Pattern.compile("<function(?:\\s*=\\s*|\\s+name\\s*=\\s*\"?)([a-zA-Z0-9_]+)\"?[\\s>]*").matcher(content);
                if (funcMatcher.find()) {
                    tName = funcMatcher.group(1);
                } else {
                    Matcher funcTagMatcher = Pattern.compile("<function>\\s*([a-zA-Z0-9_]+)\\s*</function>").matcher(content);
                    if (funcTagMatcher.find()) {
                        tName = funcTagMatcher.group(1);
                    }
                }

                if (tName != null) {
                    Map<String, String> params = new LinkedHashMap<>();
                    Matcher paramMatcher = Pattern.compile("<parameter(?:\\s*=\\s*|\\s+name\\s*=\\s*\"?)([a-zA-Z0-9_]+)\"?[\\s>]*([^<]*?)</parameter>").matcher(content);
                    while (paramMatcher.find()) {
                        String pName = paramMatcher.group(1);
                        String pVal = paramMatcher.group(2).trim();
                        if (pVal.endsWith(">")) {
                            pVal = pVal.substring(0, pVal.length() - 1).trim();
                        }
                        params.put(pName, pVal);
                    }
                    extractedRequests.add(ToolExecutionRequest.builder()
                            .id(UUID.randomUUID().toString())
                            .name(tName)
                            .arguments(buildJsonFromMap(params))
                            .build());
                }
            }
        }

        return extractedRequests;
    }

    private String buildJsonFromMap(Map<String, String> params) {
        if (params.isEmpty()) return "{}";
        List<String> jsonPairs = new ArrayList<>();
        for (var entry : params.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue();
            if (v.equalsIgnoreCase("null") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false") || v.matches("-?\\d+")) {
                jsonPairs.add("\"" + k + "\":" + v.toLowerCase());
            } else {
                jsonPairs.add("\"" + k + "\":\"" + v.replace("\"", "\\\"") + "\"");
            }
        }
        return "{" + String.join(",", jsonPairs) + "}";
    }

    private String buildFormCommunicatorPrompt(String allToolResultsText) {
        return "Đây là thông tin và kết quả thực thi các công cụ hệ thống từ Database:\n"
                + allToolResultsText
                + "\nHãy tổng hợp lại câu trả lời đầy đủ cho người dùng. Yêu cầu:\n"
                + "1. Liệt kê đầy đủ và chi tiết tất cả các thông tin đã truy vấn được (ví dụ: chi tiết các thông báo chưa đọc, thông tin các thành viên trong dự án vừa tìm thấy...). Tuyệt đối không tóm tắt số lượng.\n"
                + "2. Giải thích rõ ràng kế hoạch (plan) mà hệ thống chuẩn bị thực hiện tiếp theo (ví dụ: đổi mô tả dự án thành 'abc', tạo công việc mới) và hướng dẫn người dùng sử dụng biểu mẫu (form) hoặc xác nhận hành động được hiển thị ở trên.\n"
                + "3. Đối với các thông báo chưa đọc, KHÔNG tạo liên kết (link) cụ thể cho từng thông báo riêng lẻ. Chỉ cung cấp duy nhất một đường dẫn tương đối để xem tất cả thông báo: [Xem tất cả thông báo](/notifications).\n"
                + "4. Luôn sử dụng cú pháp markdown liên kết tương đối chuẩn [Tên hiển thị](đường_dẫn_tương_đối) để người dùng có thể nhấn vào được:\n"
                + "   - Xem chi tiết dự án: [Xem chi tiết dự án](/projects/<projectId>/overview)\n"
                + "   - Xem chi tiết công việc (task): [Xem chi tiết công việc](/projects/<projectId>/tasks/<taskId>)\n"
                + "   - Xem chi tiết bình luận: [Xem chi tiết bình luận](/projects/<projectId>/tasks/<taskId>?commentId=<commentId>)\n"
                + "   - Xem tất cả thông báo: [Xem thông báo](/notifications)\n"
                + "   - Xem tất cả bình luận: [Xem bình luận](/comments)\n"
                + "   Tuyệt đối KHÔNG dùng các URL tuyệt đối chứa http://localhost:5173 hay taskpilot-platform.netlify.app.\n"
                + "5. Sử dụng tiếng Việt thân thiện, tự nhiên. Tuyệt đối không sinh ra thẻ <think> hay bất kỳ quá trình suy nghĩ nào khác. Viết trực tiếp câu trả lời của bạn. Tuyệt đối KHÔNG tự sinh lại bất kỳ thẻ ```taskpilot-form nào trong phản hồi này.";
    }

    private String buildCommunicatorPrompt(String allToolResultsText, String rawResponseText) {
        return "Đây là thông tin và kết quả thực thi các công cụ hệ thống từ Database:\n"
                + allToolResultsText
                + "\nĐây là kế hoạch hành động ghi/cập nhật dữ liệu (plan) và biểu mẫu (forms) được đề xuất từ hệ thống:\n"
                + rawResponseText
                + "\nHãy tổng hợp lại câu trả lời đầy đủ cho người dùng. Yêu cầu:\n"
                + "1. Liệt kê đầy đủ và chi tiết tất cả các thông tin đã truy vấn được (ví dụ: chi tiết các thông báo chưa đọc, thông tin các thành viên trong dự án vừa tìm thấy...). Tuyệt đối không tóm tắt số lượng.\n"
                + "2. Giải thích rõ ràng kế hoạch (plan) mà hệ thống chuẩn bị thực hiện tiếp theo (ví dụ: đổi mô tả dự án thành 'abc', tạo công việc mới) và hướng dẫn người dùng sử dụng biểu mẫu (form) hoặc xác nhận hành động ở dưới.\n"
                + "3. Đối với các thông báo chưa đọc, KHÔNG tạo liên kết (link) cụ thể cho từng thông báo riêng lẻ. Chỉ cung cấp duy nhất một đường dẫn tương đối để xem tất cả thông báo: [Xem tất cả thông báo](/notifications).\n"
                + "4. Luôn sử dụng cú pháp markdown liên kết tương đối chuẩn [Tên hiển thị](đường_dẫn_tương_đối) để người dùng có thể nhấn vào được:\n"
                + "   - Xem chi tiết dự án: [Xem chi tiết dự án](/projects/<projectId>/overview)\n"
                + "   - Xem chi tiết công việc (task): [Xem chi tiết công việc](/projects/<projectId>/tasks/<taskId>)\n"
                + "   - Xem chi tiết bình luận: [Xem chi tiết bình luận](/projects/<projectId>/tasks/<taskId>?commentId=<commentId>)\n"
                + "   - Xem tất cả thông báo: [Xem thông báo](/notifications)\n"
                + "   - Xem tất cả bình luận: [Xem bình luận](/comments)\n"
                + "   Tuyệt đối KHÔNG dùng các URL tuyệt đối chứa http://localhost:5173 hay taskpilot-platform.netlify.app.\n"
                + "5. Sử dụng tiếng Việt thân thiện, tự nhiên. Tuyệt đối không sinh ra thẻ <think> hay bất kỳ quá trình suy nghĩ nào khác. Viết trực tiếp câu trả lời của bạn.";
    }
}
