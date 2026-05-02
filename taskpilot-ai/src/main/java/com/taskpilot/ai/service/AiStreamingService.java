package com.taskpilot.ai.service;

import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.entity.ChatMessageEntity.SenderType;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.heuristic.HeuristicConfigProvider;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.ai.tools.ToolExecutionContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiStreamingService {

    private static final int MAX_TOOL_ROUNDS = 4;
    private static final int MAX_CONSECUTIVE_SAME_TOOL_EXECUTIONS = 3;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SmartRoutingService routingService;
    private final AiLogService aiLogService;
    private final ChatStreamStatusService chatStreamStatusService;
    private final SessionChatMemoryService sessionChatMemoryService;
    private final ToolCallingRegistryService toolCallingRegistryService;
    private final HeuristicConfigProvider heuristicConfigProvider;

    @Value("${ai.chat.max-output-tokens:3500}")
    private int maxOutputTokens;

    private static final String MASTER_PROMPT_TEMPLATE = """
            You are the Senior Project Manager (TaskPilot Agent) of the TaskPilot system. Your task is to
            recommend task assignments based on analytical data.

            [CURRENT SYSTEM CONTEXT]
            - Today's Date: {{current_date}}
            - Current Assignment Mode: {{current_mode}}

            [REASONING OBJECTIVES & TRADE-OFFS]
            You MUST perform an internal reasoning process before providing your final recommendation. You are
            not a simple calculator; you are a strategic manager. You must balance the candidates' AHP (Analytic
            Hierarchy Process) scores, their current workload, and the 'Current Assignment Mode':
            - If Mode is 'URGENT': Prioritize the candidate with the highest expertise and fastest execution
                (highest AHP score). Workload constraints can be secondary to meeting tight deadlines.
            - If Mode is 'TRAINING': Prioritize junior members or those with an empty schedule to give them
                hands-on experience and foster team growth, even if their AHP score is slightly lower.
            - If Mode is 'BALANCED': Find the optimal harmony between a high AHP score and an unburdened
                schedule to avoid overloading any single team member.

            [STRICT OUTPUT RULES]
            1. Step 1 (Thinking): All of your internal reasoning, comparisons, and strategic trade-offs MUST be
                enclosed exactly within <think> and </think> tags.
            2. Step 2 (Communicating): After the closing </think> tag, provide your final recommendation clearly
                and professionally to the user. CRITICAL REQUIREMENT: You MUST extract the key data, metrics, or a markdown table (e.g., AHP scores, workload stats, overdue tasks) and present them IN YOUR FINAL RESPONSE outside the <think> tag. This ensures the user sees the concrete evidence for your decision.
            3. PROHIBITED ACTION: You MUST NEVER justify your choice by simply stating "because they have the
                highest score" or "due to the highest AHP score". You must explain your decision using
                professional management terminology (e.g., "to optimize resource allocation", "to ensure project
                timelines", or "to foster skill development").
            """;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    @Transactional
    public SseEmitter streamChat(Long sessionId, Long userId, String userInput, String clientMessageId) {
        ChatSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new SecurityException("Session not found or access denied"));

        SseEmitter emitter = new SseEmitter(180_000L);
        // Bug fix #4: per-request guard so emitter.complete() is never called twice
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

        String systemPrompt = buildSystemPrompt();
        List<ChatMessage> history = sessionChatMemoryService.appendUserMessage(sessionId, systemPrompt, userInput);
        List<ChatMessage> requestHistory = withSystemPrompt(history, systemPrompt);

        chatStreamStatusService.updatePhase(sessionId, effectiveClientMessageId, Phase.ROUTING, null, null, null);
        safeSend(emitter, "phase", Phase.ROUTING.name(), null);

        String contextText = requestHistory.stream().map(m -> {
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

        String routingInput = latestUserMessageText(requestHistory, userInput);
        SmartRoutingService.RoutingDecision decision = routingService.route(routingInput, contextText);
        StreamingChatModel selectedModel = decision.model();
        String modelName = decision.modelName();
        boolean requiresAHP = decision.requiresAHP();

        chatStreamStatusService.updatePhase(sessionId, effectiveClientMessageId, Phase.THINKING, modelName, null, null);

        String finalClientMessageId = effectiveClientMessageId;
        executor.submit(() -> {
            safeSend(emitter, "model", modelName, null);
            safeSend(emitter, "phase", Phase.THINKING.name(), null);
            doStream(emitter, emitterCompleted, session, sessionId, userId, userInput, requestHistory, systemPrompt,
                    selectedModel, modelName, startTime, false, finalClientMessageId, requiresAHP);
        });

        emitter.onTimeout(() -> {
            log.warn("[SSE] SseEmitter timed out for session {}", sessionId);
            emitter.complete();
        });

        emitter.onError(e -> {
            if (isClientAbort(e)) {
                log.debug("[SSE] SseEmitter client disconnect for session {}: {}", sessionId, e.getMessage());
                return;
            }
            log.error("[SSE] SseEmitter error for session {}", sessionId, e);
        });

        return emitter;
    }

    private void doStream(SseEmitter emitter,
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
            boolean requiresAHP) {

        List<ChatMessage> workingHistory = new ArrayList<>(history);
        StringBuilder fullResponse = new StringBuilder();
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        AtomicBoolean generatingMarked = new AtomicBoolean(false);

        List<Map<String, Object>> toolCallSummaries = new ArrayList<>();
        LinkedHashSet<String> toolNames = new LinkedHashSet<>();

        streamRound(
                emitter,
                emitterCompleted,
                session,
                sessionId,
                userId,
                userInput,
                workingHistory,
                systemPrompt,
                model,
                modelName,
                startTime,
                isFallbackAttempt,
                clientMessageId,
                fullResponse,
                clientDisconnected,
                generatingMarked,
                0,
                requiresAHP,
                null,
                0,
                toolCallSummaries,
                toolNames);
    }

    private void streamRound(SseEmitter emitter,
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
            String lastToolName,
            int consecutiveToolExecutions,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames) {
        List<ChatMessage> sanitizedHistory = sanitizeHistoryForTools(history);
        // Default: No tools, no toolChoice. Only override when explicitly needed.
        List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecs = toolCallingRegistryService
                .toolSpecifications();
        ToolChoice toolChoice = null;

        if (requiresAHP) {
            if (toolRound == 0) {
                // Round 0: Force AHP tool call
                List<dev.langchain4j.agent.tool.ToolSpecification> ahpOnly = toolCallingRegistryService
                        .toolSpecificationsByName("recommendAssignmentCandidates");
                if (!ahpOnly.isEmpty()) {
                    toolSpecs = ahpOnly;
                    // Bug fix: Groq 120b crashes when ToolChoice.REQUIRED is used if the model fails to call it.
                    // Using AUTO prevents the 400 error while still allowing the model to call the tool.
                    toolChoice = ToolChoice.AUTO;
                    log.info("[Gatekeeper] requiresAHP=true -> forcing recommendAssignmentCandidates");
                } else {
                    log.warn("[Gatekeeper] requiresAHP=true but recommendAssignmentCandidates tool not found");
                }
            } else {
                // Round 1+: All tool results are already injected into history by sanitizeHistoryForTools().
                // Do NOT call streamRound() again — that still sends parallel_tool_calls and causes 400.
                // Instead, delegate immediately to forceTextOnlyResponse().
                log.info("[Gatekeeper] requiresAHP=true -> disabling further tool rounds, routing to text-only");
                forceTextOnlyResponse(
                        emitter, emitterCompleted, session, sessionId, userId, userInput,
                        history, systemPrompt, model, modelName, startTime,
                        isFallbackAttempt, clientMessageId, fullResponse,
                        clientDisconnected, generatingMarked, requiresAHP,
                        toolCallSummaries, toolNames,
                        "Based on the tool data already provided in the context above, provide your final recommendation now. Do not call any tools.");
                return;
            }
        }

        var requestBuilder = ChatRequest.builder()
                .messages(sanitizedHistory)
                .maxOutputTokens(maxOutputTokens);

        // Only add tools/toolChoice if actually needed.
        if (toolSpecs != null && !toolSpecs.isEmpty()) {
            requestBuilder.toolSpecifications(toolSpecs);
            if (toolChoice != null) {
                requestBuilder.toolChoice(toolChoice);
            }
        }

        ChatRequest request = requestBuilder.build();

        model.chat(request, new StreamingChatResponseHandler() {
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
                    if (!safeSend(emitter, "token", Map.of("token", partialResponse), MediaType.APPLICATION_JSON)) {
                        clientDisconnected.set(true);
                    }
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                AiMessage aiMessage = completeResponse.aiMessage();

                if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                    ToolLoopState nextToolState = advanceToolLoopState(
                            lastToolName,
                            consecutiveToolExecutions,
                            aiMessage.toolExecutionRequests());

                    if (nextToolState.consecutiveCount() > MAX_CONSECUTIVE_SAME_TOOL_EXECUTIONS) {
                        log.warn("[AiChat] Tool loop guard hit for session {}: tool={} repeated {} times",
                                sessionId, nextToolState.toolName(), nextToolState.consecutiveCount());
                        forceTextOnlyResponse(
                                emitter,
                                emitterCompleted,
                                session,
                                sessionId,
                                userId,
                                userInput,
                                history,
                                systemPrompt,
                                model,
                                modelName,
                                startTime,
                                isFallbackAttempt,
                                clientMessageId,
                                fullResponse,
                                clientDisconnected,
                                generatingMarked,
                                requiresAHP,
                                toolCallSummaries,
                                toolNames,
                                "The same tool was requested too many times. Provide a final answer using the data already gathered, without calling more tools.");
                        return;
                    }

                    if (toolRound >= MAX_TOOL_ROUNDS) {
                        chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                                Phase.FAILED, modelName, null,
                                "Max tool rounds exceeded");
                        safeSend(emitter, "phase", Phase.FAILED.name(), null);
                        safeSend(emitter, "error",
                                "Tool execution exceeded allowed rounds. Please refine your request.",
                                null);
                        safeComplete(emitter, emitterCompleted);
                        return;
                    }

                    history.add(aiMessage);
                    List<ToolExecutionResultMessage> toolResults = executeTools(
                            aiMessage.toolExecutionRequests(),
                            emitter,
                            toolCallSummaries,
                            toolNames,
                            userId,
                            sessionId);
                    history.addAll(toolResults);

                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.THINKING, modelName, null, null);
                    safeSend(emitter, "phase", Phase.THINKING.name(), null);

                    if (requiresAHP) {
                        // AHP flow: tool result is already sanitized into history by the next
                        // streamRound() call's sanitizeHistoryForTools(). Skip streamRound() entirely
                        // to avoid sending parallel_tool_calls without tools (OpenAI 400 error).
                        // Route directly to a clean text-only call.
                        log.info("[Gatekeeper] requiresAHP=true -> tool done, routing to forceTextOnlyResponse");
                        forceTextOnlyResponse(
                                emitter, emitterCompleted, session, sessionId, userId, userInput,
                                history, systemPrompt, model, modelName, startTime,
                                isFallbackAttempt, clientMessageId, fullResponse,
                                clientDisconnected, generatingMarked, requiresAHP,
                                toolCallSummaries, toolNames,
                                "Based on the tool data already provided in the context above, provide your final strategic recommendation now. Do not call any tools.");
                    } else {
                        streamRound(
                                emitter,
                                emitterCompleted,
                                session,
                                sessionId,
                                userId,
                                userInput,
                                history,
                                systemPrompt,
                                model,
                                modelName,
                                startTime,
                                isFallbackAttempt,
                                clientMessageId,
                                fullResponse,
                                clientDisconnected,
                                generatingMarked,
                                toolRound + 1,
                                requiresAHP,
                                nextToolState.toolName(),
                                nextToolState.consecutiveCount(),
                                toolCallSummaries,
                                toolNames);
                    }
                    return;
                }

                long durationMs = System.currentTimeMillis() - startTime;
                String responseText = fullResponse.toString();
                if ((responseText == null || responseText.isBlank()) && aiMessage != null && aiMessage.text() != null) {
                    responseText = aiMessage.text();
                }

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
                    // Bug fix #8: strip <think> block before using as title
                    String titleSource = stripThinkTags(responseText);
                    String autoTitle = titleSource.length() > 60
                            ? titleSource.substring(0, 60) + "..."
                            : titleSource;
                    session.setTitle(autoTitle);
                }
                sessionRepository.save(session);

                Object toolOutput = toolCallSummaries.isEmpty() ? null : toolCallSummaries;
                String actionTaken = toolNames.isEmpty() ? null : String.join(",", toolNames);

                aiLogService.saveLog(userId, null, sessionId, assistantMsg.getId(), userInput,
                        responseText, extractReasoning(responseText), actionTaken, toolOutput, modelName,
                        estimatedTokens, (int) durationMs);

                String cleanResponse = sessionChatMemoryService.sanitizeAssistantMessage(responseText);
                sessionChatMemoryService.appendAssistantMessage(sessionId, cleanResponse, systemPrompt);

                chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                        Phase.FINALIZED, modelName, assistantMsg.getId(), null);

                if (!clientDisconnected.get()) {
                    safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
                    safeSend(emitter, "done", responseText, null);
                    safeComplete(emitter, emitterCompleted);
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
                        safeComplete(emitter, emitterCompleted);
                        return;
                    }

                    String fallbackName = routingService.getModelName(fallback);
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.THINKING, fallbackName, null, null);
                    safeSend(emitter, "model", fallbackName + " (fallback)", null);
                    safeSend(emitter, "phase", Phase.THINKING.name(), null);

                    // Bug fix #3: pass fresh StringBuilder so fallback doesn't inherit partial tokens
                    // from the failed primary model's output.
                    doStream(emitter, emitterCompleted, session, sessionId, userId, userInput, history, systemPrompt, fallback,
                            fallbackName, startTime, true, clientMessageId, requiresAHP);
                } else {
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.FAILED, modelName, null, error.getMessage());
                    safeSend(emitter, "phase", Phase.FAILED.name(), null);
                    safeSend(emitter, "error",
                            "AI service is currently unavailable. Please try again later.",
                            null);
                    safeComplete(emitter, emitterCompleted);
                }
            }
        });
    }

    private void forceTextOnlyResponse(SseEmitter emitter,
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

        List<ChatMessage> textOnlyHistory = new ArrayList<>(sanitizeHistoryForTools(history));
        textOnlyHistory.add(SystemMessage.from(guardrailInstruction));

        // Bug fix #2: use a fresh, isolated StringBuilder so we don't contaminate
        // the shared fullResponse with stray partial tokens from earlier rounds.
        StringBuilder roundResponse = new StringBuilder();

        // Architectural Fix: Dual-Pipeline.
        // Instead of reusing the tool-enabled model, we fetch the pure TEXT-ONLY variant 
        // of the same provider to avoid the parallel_tool_calls bug cleanly without dummy tools.
        StreamingChatModel textModel = routingService.getTextModel(modelName);
        log.info("[ForceTextOnly] Using text-only pipeline model {} for session {}", modelName, sessionId);

        ChatRequest request = ChatRequest.builder()
                .messages(textOnlyHistory)
                .maxOutputTokens(maxOutputTokens)
                .build();

        textModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                roundResponse.append(partialResponse);

                if (generatingMarked.compareAndSet(false, true)) {
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.GENERATING, modelName, null, null);
                    if (!safeSend(emitter, "phase", Phase.GENERATING.name(), null)) {
                        clientDisconnected.set(true);
                    }
                }

                if (!clientDisconnected.get()) {
                    if (!safeSend(emitter, "token", Map.of("token", partialResponse), MediaType.APPLICATION_JSON)) {
                        clientDisconnected.set(true);
                    }
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                AiMessage aiMessage = completeResponse.aiMessage();
                String responseText = roundResponse.toString();
                if ((responseText == null || responseText.isBlank()) && aiMessage != null && aiMessage.text() != null) {
                    responseText = aiMessage.text();
                }

                if (responseText == null || responseText.isBlank()) {
                    responseText = "I couldn't complete the tool-based reasoning safely, so please refine the request and try again.";
                }

                long durationMs = System.currentTimeMillis() - startTime;
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
                    // Bug fix #8: strip <think> block before using as title
                    String titleSource = stripThinkTags(responseText);
                    String autoTitle = titleSource.length() > 60
                            ? titleSource.substring(0, 60) + "..."
                            : titleSource;
                    session.setTitle(autoTitle);
                }
                sessionRepository.save(session);

                Object toolOutput = toolCallSummaries.isEmpty() ? null : toolCallSummaries;
                String actionTaken = toolNames.isEmpty() ? null : String.join(",", toolNames);

                // Log with modelName so it's clear which model actually generated the response
                aiLogService.saveLog(userId, null, sessionId, assistantMsg.getId(), userInput,
                        responseText, extractReasoning(responseText), actionTaken, toolOutput, modelName,
                        estimatedTokens, (int) durationMs);

                String cleanResponse = sessionChatMemoryService.sanitizeAssistantMessage(responseText);
                sessionChatMemoryService.appendAssistantMessage(sessionId, cleanResponse, systemPrompt);

                chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                        Phase.FINALIZED, modelName, assistantMsg.getId(), null);

                if (!clientDisconnected.get()) {
                    safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
                    safeSend(emitter, "done", responseText, null);
                    safeComplete(emitter, emitterCompleted);
                }

                log.info("[SSE] forceTextOnly finalized session {} via {} in {}ms",
                        sessionId, modelName, durationMs);
            }

            @Override
            public void onError(Throwable error) {
                if (clientDisconnected.get() || isClientAbort(error)) {
                    return;
                }

                // Always log — this was previously swallowed silently, making bugs invisible
                log.error("[SSE] forceTextOnlyResponse failed for session {} model {}: {}",
                        sessionId, modelName, error.getMessage());

                chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                        Phase.FAILED, modelName, null, error.getMessage());
                safeSend(emitter, "phase", Phase.FAILED.name(), null);
                safeSend(emitter, "error",
                        "AI service is currently unavailable. Please try again later.",
                        null);
                safeComplete(emitter, emitterCompleted);
            }
        });
    }

    private ToolLoopState advanceToolLoopState(String previousToolName,
            int previousCount,
            List<ToolExecutionRequest> requests) {
        String currentToolName = previousToolName;
        int currentCount = previousCount;

        for (ToolExecutionRequest request : requests) {
            String requestToolName = request.name();
            if (requestToolName != null && requestToolName.equals(currentToolName)) {
                currentCount++;
            } else {
                currentToolName = requestToolName;
                currentCount = 1;
            }
        }

        return new ToolLoopState(currentToolName, currentCount);
    }

    private List<ToolExecutionResultMessage> executeTools(
            List<ToolExecutionRequest> requests,
            SseEmitter emitter,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames,
            Long userId,
            Long sessionId) {
        List<ToolExecutionResultMessage> results = new ArrayList<>();

        for (ToolExecutionRequest request : requests) {
            String output;
            ToolExecutionContext.set(new ToolExecutionContext.Context(userId, sessionId));
            try {
                output = toolCallingRegistryService.execute(request);
            } finally {
                ToolExecutionContext.clear();
            }

            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("name", request.name());
            eventPayload.put("arguments", request.arguments());
            eventPayload.put("result", truncate(output, 1500));
            safeSend(emitter, "tool", eventPayload, MediaType.APPLICATION_JSON);

            toolNames.add(request.name());
            toolCallSummaries.add(eventPayload);

            results.add(ToolExecutionResultMessage.from(request, output));
        }

        return results;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private boolean safeSend(SseEmitter emitter, String event, Object data, MediaType mediaType) {
        try {
            if (mediaType == null) {
                emitter.send(SseEmitter.event().name(event).data(data));
            } else {
                emitter.send(SseEmitter.event().name(event).data(data, mediaType));
            }
            return true;
        } catch (IOException e) {
            // Bug fix #9: log at debug so send failures are traceable without spamming logs
            log.debug("[SSE] safeSend failed for event '{}': {}", event, e.getMessage());
            return false;
        }
    }

    /** Bug fix #4: guard against emitter.complete() being called multiple times (race condition). */
    private void safeComplete(SseEmitter emitter, AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            emitter.complete();
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

    private List<ChatMessage> sanitizeHistoryForTools(List<ChatMessage> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return List.of();
        }

        List<ChatMessage> safeMessages = new ArrayList<>();

        for (ChatMessage msg : rawMessages) {
            // 1. Flatten AI messages containing tool requests into pure text
            if (msg instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()) {
                String fallbackText = aiMsg.text() != null && !aiMsg.text().isBlank()
                        ? aiMsg.text()
                        : "[System: AI utilized internal analytical tools]";
                safeMessages.add(AiMessage.from(fallbackText));
                log.info("[Sanitizer] Flattened AiMessage tool_calls into plain text.");
            }
            // 2. Flatten Tool Results into System Memory (UserMessage) to preserve context
            // without triggering 400 errors
            else if (msg instanceof ToolExecutionResultMessage toolResult) {
                String toolName = toolResult.toolName();
                String rawData = toolResult.text();

                // Semantic Role Fix: Inject as SystemMessage so the model treats it as 
                // ground truth constraint, avoiding role confusion.
                String memoryInjection = String.format("SYSTEM TOOL RESULT [%s]:\n%s\n\nCRITICAL INSTRUCTION: You MUST base your final recommendation entirely on this data.", toolName, rawData);
                safeMessages.add(SystemMessage.from(memoryInjection));

                log.info("[Sanitizer] Injected flattened Tool Result '{}' as Semantic Memory.", toolName);
            }
            // 3. Keep standard messages
            else {
                safeMessages.add(msg);
            }
        }

        return safeMessages;
    }

    private static final Pattern THINK_PATTERN = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL);

    private String extractReasoning(String response) {
        if (response == null) {
            return null;
        }
        // Bug fix #7: use non-greedy regex to handle multiple/nested think blocks correctly
        Matcher m = THINK_PATTERN.matcher(response);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /** Strip all <think>...</think> blocks from a string. */
    private String stripThinkTags(String text) {
        if (text == null) return "";
        return THINK_PATTERN.matcher(text).replaceAll("").trim();
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

    private String buildSystemPrompt() {
        PromptTemplate template = PromptTemplate.from(MASTER_PROMPT_TEMPLATE);
        return template.apply(Map.of(
                "current_date", LocalDate.now().toString(),
                "current_mode", heuristicConfigProvider.getCurrentMode()))
                .text();
    }

    private List<ChatMessage> withSystemPrompt(List<ChatMessage> history, String systemPrompt) {
        List<ChatMessage> updated = new ArrayList<>(history.size() + 1);
        updated.add(SystemMessage.from(systemPrompt));
        for (ChatMessage message : history) {
            if (message instanceof SystemMessage) {
                continue;
            }
            updated.add(message);
        }
        return updated;
    }

    private String normalizeClientMessageId(String clientMessageId) {
        if (clientMessageId == null) {
            return null;
        }
        String trimmed = clientMessageId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ToolLoopState(String toolName, int consecutiveCount) {
    }
}
