package com.taskpilot.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    private final ThinkingNarratorService thinkingNarratorService;
    private final TokenCountEstimator tokenCountEstimator;
    private final ObjectMapper objectMapper;

    @Value("${ai.chat.max-output-tokens:3500}")
    private int maxOutputTokens;

    @Value("${ai.chat.stream-first-response-timeout-seconds:25}")
    private int streamFirstResponseTimeoutSeconds;

    @Value("${ai.chat.text-only-first-response-timeout-seconds:10}")
    private int textOnlyFirstResponseTimeoutSeconds;

    @Value("${ai.chat.memory-max-tokens:7000}")
    private int maxContextTokens;

    @Value("${ai.chat.context-tail-messages:6}")
    private int contextTailMessages;

    @Value("${ai.chat.compact-summary-max-chars:3000}")
    private int compactSummaryMaxChars;

    @Value("${ai.chat.compact-message-max-chars:600}")
    private int compactMessageMaxChars;

    @Value("${ai.chat.max-tool-result-memory-chars:12000}")
    private int maxToolResultMemoryChars;

    private static final String MASTER_PROMPT_TEMPLATE = """
            You are the Senior Project Manager (TaskPilot Agent) of the TaskPilot system. Your task is to
            recommend task assignments based on analytical data.

            [CURRENT SYSTEM CONTEXT]
            - Today's Date: {{current_date}}
            - Current Assignment Mode: {{current_mode}}

            [TASKPILOT TOOL WORKFLOW RULES]
            - Vietnamese shorthand matters: "ch", "chua", "chưa", "ch dc", "ch đc", "chua duoc", and
              "chưa được" mean "not yet". For task assignment questions, interpret them as unassigned/not assigned.
            - If the user names a specific assignee (for example "cho Julia Design", "gán cho Ian",
              "assign task 68 to Julia"), the user's explicit assignee overrides the recommendation algorithm.
              In that case call assignTaskToMemberByName or assignTaskToMember after resolving the member; do NOT
              call recommendAndAssignTask, because recommendAndAssignTask always picks the top ranked candidate.
            - If the user asks which tasks are not assigned yet in a project, call getUnassignedTasksByProject.
              Do not answer from the full task list unless the unassigned-only tool is unavailable.
            - If the user asks for unassigned tasks in the project that contains a task ID (for example
              "du an co chua task 67"), first call getTaskDetails(taskId) to resolve projectId, then call
              getUnassignedTasksByProject(projectId).
            - If the user asks to recommend a suitable assignee and also apply the assignment, call
              recommendAndAssignTask for each concrete task. This is a real data write action.
            - If task skills or difficulty are missing, ask for only the missing fields. The frontend may provide
              those fields as a structured "Task assignment requirements form"; use that structured data directly.
              When the user provides missing task skills for assignment, call recommendAndAssignTask with those
              skills and difficulty so the pending confirmation saves the task skills and assigns the task together.
              If the user only wants to update task skills, call updateTaskRequiredSkills.
            - Multi-step user requests are allowed. You may call tools repeatedly across rounds when needed:
              first read data, then recommend candidates, then write assignments if the user clearly asked to apply.
            - Any create/update/delete/assignment tool may return confirmationRequired=true instead of writing data.
              In that case, tell the user exactly what will change and wait for a final confirmation. Do not claim
              the change has been applied until confirmPendingAction returns a success result.
            - When you need additional structured information from the user, include a fenced `taskpilot-form`
              JSON block so the frontend can render an interactive form. Do this for any workflow where a form can
              represent the missing fields; do not ask only in plain text for structured fields.
              Supported field types are text, number, textarea, select, date, and checkbox. Ask only for fields
              that are missing.
              Example:
              ```taskpilot-form
              {"title":"Bo sung thong tin","description":"Nhap cac truong con thieu de tiep tuc.","submitLabel":"Gui thong tin","intent":"continue_previous_request","fields":[{"name":"taskId","label":"Task ID","type":"number","required":true}]}
              ```

            [REASONING OBJECTIVES & TRADE-OFFS]
            Think privately before providing your final recommendation. You are not a simple calculator; you are
            a strategic manager. Balance the candidates' AHP (Analytic Hierarchy Process) scores, their current
            workload, and the 'Current Assignment Mode'.

            Your private reasoning process should be granular and structured:
            - Step 1: Analyze user intent and project requirements.
            - Step 2: Retrieve relevant data using available tools (if needed).
            - Step 3: Evaluate results, compare candidates, and weigh trade-offs based on the 'Current Assignment Mode'.
            - Step 4: Formulate the final strategic recommendation.

            [STRICT OUTPUT RULES]
            1. Respond in Vietnamese by default. If the user writes in another language, mirror that language.
            2. Do not output hidden reasoning tags such as <think>, </think>, <Dthink>, or </Dthink>.
            3. Provide the final recommendation clearly and professionally. Include key data, metrics, or a
                markdown table when useful so the user sees the concrete evidence for your decision.
            4. When a write tool returns confirmationRequired=true, explain the pending change in Vietnamese and
                tell the user they can approve or reject it in the confirmation card. Do not claim the change has
                been applied until confirmPendingAction returns a success result.
            5. PROHIBITED ACTION: You MUST NEVER justify your choice by simply stating "because they have the
                highest score" or "due to the highest AHP score". You must explain your decision using
                professional management terminology (e.g., "to optimize resource allocation", "to ensure project
                timelines", or "to foster skill development").
            """;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        timeoutScheduler.shutdownNow();
    }

    public SseEmitter streamChat(Long sessionId, Long userId, String userInput, String clientMessageId) {
        ChatSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new SecurityException("Session not found or access denied"));

        SseEmitter emitter = new SseEmitter(180_000L);
        log.info("[SSE] AI chat stream opened for session {}", sessionId);
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
            safeComplete(emitter, emitterCompleted);
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
        List<ChatMessage> requestHistory = compactHistoryForRequest(
                withSystemPrompt(history, systemPrompt),
                "initial");

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
            safeComplete(emitter, emitterCompleted);
        });

        emitter.onCompletion(() -> log.debug("[SSE] AI chat stream completed/closed for session {}", sessionId));

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
        List<ChatMessage> sanitizedHistory = compactHistoryForRequest(
                sanitizeHistoryForTools(history),
                "tool-round-" + toolRound);
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

                final AtomicBoolean roundClosed = new AtomicBoolean(false);
                final AtomicBoolean firstModelSignalReceived = new AtomicBoolean(false);
                final boolean suppressTextUntilToolCall = requiresAHP && toolRound == 0;
                final ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(() -> {
                    if (firstModelSignalReceived.get() || clientDisconnected.get() || emitterCompleted.get()) {
                        return;
                    }
                    if (roundClosed.compareAndSet(false, true)) {
                        handleFirstResponseTimeout(
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
                                requiresAHP);
                    }
                }, Math.max(1, streamFirstResponseTimeoutSeconds), TimeUnit.SECONDS);

                final StringBuilder thinkingBuffer = new StringBuilder();
                final AtomicBoolean insideThink = new AtomicBoolean(false);

                model.chat(request, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (roundClosed.get()) {
                            return;
                        }
                        firstModelSignalReceived.set(true);
                        if (suppressTextUntilToolCall) {
                            return;
                        }
                        fullResponse.append(partialResponse);

                        // Thinking expansion logic: buffer tokens between <think> and </think>
                        if (partialResponse.contains("<think>")) {
                            insideThink.set(true);
                        }
                        if (insideThink.get()) {
                            thinkingBuffer.append(partialResponse);
                        }
                        if (partialResponse.contains("</think>")) {
                            insideThink.set(false);
                        }

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
                        firstModelSignalReceived.set(true);
                        if (!roundClosed.compareAndSet(false, true)) {
                            return;
                        }
                        timeoutFuture.cancel(false);
                        // After completion, if we have a thinking buffer, expand it in the background
                        String rawThinking = thinkingBuffer.toString();
                        if (rawThinking.contains("<think>")) {
                            String thinkingContent = extractAllThinkBlocks(rawThinking);
                            if (thinkingContent != null && !thinkingContent.isBlank()) {
                                thinkingNarratorService.expandAsync(thinkingContent).thenAccept(expanded -> {
                                    log.info("[AiChat] Expanded thinking for session {}", sessionId);
                                    safeSend(emitter, "thought_expanded", Map.of("expanded", expanded), MediaType.APPLICATION_JSON);
                                });
                            }
                        }

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
                            sessionId,
                            userInput);
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

                if (requiresAHP && toolRound == 0) {
                    String nonToolText = aiMessage == null ? "" : aiMessage.text();
                    log.warn("[Gatekeeper] requiresAHP=true but model {} completed without recommendAssignmentCandidates. Retrying fallback. Text was: {}",
                            modelName, abbreviateForLog(nonToolText, 160));

                    if (routingService.hasStreamingFallbackAfter(model)) {
                        StreamingChatModel fallback = routingService.getNextStreamingFallback(model);
                        String fallbackName = routingService.getModelName(fallback);
                        chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                                Phase.THINKING, fallbackName, null, null);
                        safeSend(emitter, "model", fallbackName + " (tool-call fallback)", null);
                        safeSend(emitter, "phase", Phase.THINKING.name(), null);
                        streamRound(
                                emitter,
                                emitterCompleted,
                                session,
                                sessionId,
                                userId,
                                userInput,
                                history,
                                systemPrompt,
                                fallback,
                                fallbackName,
                                startTime,
                                !routingService.hasStreamingFallbackAfter(fallback),
                                clientMessageId,
                                fullResponse,
                                clientDisconnected,
                                generatingMarked,
                                toolRound,
                                requiresAHP,
                                lastToolName,
                                consecutiveToolExecutions,
                                toolCallSummaries,
                                toolNames);
                        return;
                    }

                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.FAILED, modelName, null,
                            "Model completed without required AHP tool call");
                    safeSend(emitter, "phase", Phase.FAILED.name(), null);
                    safeSend(emitter, "error",
                            "AI service could not start the assignment workflow. Please try again.",
                            null);
                    safeComplete(emitter, emitterCompleted);
                    return;
                }

                long durationMs = System.currentTimeMillis() - startTime;
                String rawResponseText = fullResponse.toString();
                if ((rawResponseText == null || rawResponseText.isBlank()) && aiMessage != null && aiMessage.text() != null) {
                    rawResponseText = aiMessage.text();
                }
                String responseText = appendTaskPilotBlocks(stripThinkBlocks(rawResponseText), toolCallSummaries);
                String extractedReasoning = extractAllThinkBlocks(rawResponseText);

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
                    String titleSource = stripThinkBlocks(responseText);
                    String autoTitle = titleSource.length() > 60
                            ? titleSource.substring(0, 60) + "..."
                            : titleSource;
                    session.setTitle(autoTitle);
                }
                sessionRepository.save(session);

                Object toolOutput = toolCallSummaries.isEmpty() ? null : toolCallSummaries;
                String actionTaken = toolNames.isEmpty() ? null : String.join(",", toolNames);

                aiLogService.saveLog(userId, null, sessionId, assistantMsg.getId(), userInput,
                        responseText, extractedReasoning, actionTaken, toolOutput, modelName,
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
                firstModelSignalReceived.set(true);
                if (!roundClosed.compareAndSet(false, true)) {
                    return;
                }
                timeoutFuture.cancel(false);
                if (clientDisconnected.get() || isClientAbort(error)) {
                    log.debug("[SSE] Client aborted stream for session {} (model {}): {}",
                            sessionId, modelName, error.getMessage());
                    return;
                }

                log.error("[SSE] Model {} failed for session {}: {}", modelName, sessionId,
                        error.getMessage());

                if (!isFallbackAttempt || routingService.hasStreamingFallbackAfter(model)) {
                    StreamingChatModel fallback = routingService.getNextStreamingFallback(model);
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

                    boolean isStillGemini = routingService.isGeminiModel(fallback);
                    String fallbackName = routingService.getModelName(fallback);
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.THINKING, fallbackName, null, null);
                    safeSend(emitter, "model",
                            fallbackName + (isStillGemini ? " (gemini fallback)" : " (fallback)"), null);
                    safeSend(emitter, "phase", Phase.THINKING.name(), null);

                    // Pass fresh stream; fallback doesn't inherit partial tokens from the failed model
                    doStream(emitter, emitterCompleted, session, sessionId, userId, userInput, history, systemPrompt, fallback,
                            fallbackName, startTime, !routingService.hasStreamingFallbackAfter(fallback), clientMessageId, requiresAHP);
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
            boolean requiresAHP) {
        String message = "Model did not produce a first streaming response within "
                + streamFirstResponseTimeoutSeconds + "s";
        log.warn("[SSE] {} for session {} using model {}", message, sessionId, modelName);

        if (!isFallbackAttempt || routingService.hasStreamingFallbackAfter(model)) {
            StreamingChatModel fallback = routingService.getNextStreamingFallback(model);
            if (fallback != model) {
                boolean isStillGemini = routingService.isGeminiModel(fallback);
                String fallbackName = routingService.getModelName(fallback);
                chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                        Phase.THINKING, fallbackName, null, null);
                safeSend(emitter, "model",
                        fallbackName + (isStillGemini ? " (gemini fallback after timeout)" : " (fallback after timeout)"),
                        null);
                safeSend(emitter, "phase", Phase.THINKING.name(), null);
                doStream(emitter, emitterCompleted, session, sessionId, userId, userInput,
                        history, systemPrompt, fallback, fallbackName, startTime,
                        !routingService.hasStreamingFallbackAfter(fallback), clientMessageId, requiresAHP);
                return;
            }
        }

        chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                Phase.FAILED, modelName, null, message);
        safeSend(emitter, "phase", Phase.FAILED.name(), null);
        safeSend(emitter, "error", "AI service is taking too long. Please try again later.", null);
        safeComplete(emitter, emitterCompleted);
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
        textOnlyHistory = new ArrayList<>(compactHistoryForRequest(textOnlyHistory, "text-only"));

        // Bug fix #2: use a fresh, isolated StringBuilder so we don't contaminate
        // the shared fullResponse with stray partial tokens from earlier rounds.
        StringBuilder roundResponse = new StringBuilder();

        // Tool data is already in the prompt at this point. Use the fast text-only
        // finalizer instead of a reasoning/tool model so the UI gets a real answer quickly.
        StreamingChatModel textModel = routingService.getFallbackTextModel();
        String textModelName = routingService.getModelName(textModel);
        log.info("[ForceTextOnly] Using fast text-only finalizer {} after {} for session {}",
                textModelName, modelName, sessionId);

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
                String fallbackResponse = buildTextOnlyTimeoutResponse(toolCallSummaries);
                log.warn("[SSE] forceTextOnly first response timed out after {}s for session {} model {}",
                        textOnlyFirstResponseTimeoutSeconds, sessionId, textModelName);
                finalizeForceTextOnlyResponse(
                        emitter,
                        session,
                        sessionId,
                        userId,
                        userInput,
                        systemPrompt,
                        textModelName,
                        startTime,
                        clientMessageId,
                        clientDisconnected,
                        toolCallSummaries,
                        toolNames,
                        fallbackResponse,
                        null);
                safeComplete(emitter, emitterCompleted);
            }
        }, Math.max(1, textOnlyFirstResponseTimeoutSeconds), TimeUnit.SECONDS);

        textModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (roundClosed.get()) {
                    return;
                }
                firstModelSignalReceived.set(true);
                roundResponse.append(partialResponse);

                if (generatingMarked.compareAndSet(false, true)) {
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.GENERATING, textModelName, null, null);
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
                firstModelSignalReceived.set(true);
                if (!roundClosed.compareAndSet(false, true)) {
                    return;
                }
                timeoutFuture.cancel(false);
                AiMessage aiMessage = completeResponse.aiMessage();
                String rawResponseText = roundResponse.toString();
                if ((rawResponseText == null || rawResponseText.isBlank()) && aiMessage != null && aiMessage.text() != null) {
                    rawResponseText = aiMessage.text();
                }
                finalizeForceTextOnlyResponse(
                        emitter,
                        session,
                        sessionId,
                        userId,
                        userInput,
                        systemPrompt,
                        textModelName,
                        startTime,
                        clientMessageId,
                        clientDisconnected,
                        toolCallSummaries,
                        toolNames,
                        rawResponseText,
                        completeResponse);
                safeComplete(emitter, emitterCompleted);
            }

            @Override
            public void onError(Throwable error) {
                firstModelSignalReceived.set(true);
                if (!roundClosed.compareAndSet(false, true)) {
                    return;
                }
                timeoutFuture.cancel(false);
                if (clientDisconnected.get() || isClientAbort(error)) {
                    return;
                }

                // Always log — this was previously swallowed silently, making bugs invisible
                log.error("[SSE] forceTextOnlyResponse failed for session {} model {}: {}",
                        sessionId, textModelName, error.getMessage());

                chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                        Phase.FAILED, textModelName, null, error.getMessage());
                safeSend(emitter, "phase", Phase.FAILED.name(), null);
                safeSend(emitter, "error",
                        "AI service is currently unavailable. Please try again later.",
                        null);
                safeComplete(emitter, emitterCompleted);
            }
        });
    }

    private void finalizeForceTextOnlyResponse(
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
            ChatResponse completeResponse) {
        String responseText = appendTaskPilotBlocks(stripThinkBlocks(rawResponseText), toolCallSummaries);
        String extractedReasoning = extractAllThinkBlocks(rawResponseText);

        if (responseText == null || responseText.isBlank()) {
            responseText = "Mình chưa tạo được câu trả lời hoàn chỉnh. Bạn thử gửi lại yêu cầu ngắn hơn một chút nhé.";
        }

        long durationMs = System.currentTimeMillis() - startTime;
        int estimatedTokens = completeResponse != null && completeResponse.tokenUsage() != null
                ? completeResponse.tokenUsage().totalTokenCount()
                : responseText.length() / 4;

        ChatMessageEntity assistantMsg = messageRepository.save(ChatMessageEntity.builder()
                .sessionId(sessionId)
                .sender(SenderType.ASSISTANT)
                .content(responseText)
                .build());

        session.setUpdatedAt(Instant.now());
        if (session.getTitle() == null || session.getTitle().isBlank()) {
            String titleSource = stripThinkBlocks(responseText);
            String autoTitle = titleSource.length() > 60
                    ? titleSource.substring(0, 60) + "..."
                    : titleSource;
            session.setTitle(autoTitle);
        }
        sessionRepository.save(session);

        Object toolOutput = toolCallSummaries.isEmpty() ? null : toolCallSummaries;
        String actionTaken = toolNames.isEmpty() ? null : String.join(",", toolNames);

        aiLogService.saveLog(userId, null, sessionId, assistantMsg.getId(), userInput,
                responseText, extractedReasoning, actionTaken, toolOutput, modelName,
                estimatedTokens, (int) durationMs);

        String cleanResponse = sessionChatMemoryService.sanitizeAssistantMessage(responseText);
        sessionChatMemoryService.appendAssistantMessage(sessionId, cleanResponse, systemPrompt);

        chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                Phase.FINALIZED, modelName, assistantMsg.getId(), null);

        if (!clientDisconnected.get()) {
            safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
            safeSend(emitter, "done", responseText, null);
        }

        log.info("[SSE] forceTextOnly finalized session {} via {} in {}ms",
                sessionId, modelName, durationMs);
    }

    private String buildTextOnlyTimeoutResponse(List<Map<String, Object>> toolCallSummaries) {
        boolean hasPendingConfirmation = toolCallSummaries != null && toolCallSummaries.stream()
                .anyMatch(summary -> summary.get("confirmation") instanceof Map<?, ?>);
        if (hasPendingConfirmation) {
            return "Mình đã chuẩn bị thao tác ghi dữ liệu và cần bạn phê duyệt trong thẻ xác nhận bên dưới. "
                    + "Bước diễn giải cuối của model phản hồi quá lâu nên mình hiển thị ngay hành động cần xác nhận.";
        }
        return "Mình đã lấy dữ liệu bằng công cụ nội bộ, nhưng bước diễn giải cuối của model phản hồi quá lâu. "
                + "Bạn thử gửi lại yêu cầu ngắn hơn hoặc yêu cầu phân công trực tiếp cho một task cụ thể nhé.";
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
            Long sessionId,
            String userInput) {
        List<ToolExecutionResultMessage> results = new ArrayList<>();

        for (ToolExecutionRequest request : requests) {
            String output;
            ToolExecutionContext.set(new ToolExecutionContext.Context(userId, sessionId, userInput));
            try {
                output = toolCallingRegistryService.execute(request);
            } finally {
                ToolExecutionContext.clear();
            }

            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("name", request.name());
            eventPayload.put("arguments", request.arguments());
            eventPayload.put("result", truncate(output, 1500));
            parseConfirmationPayload(output).ifPresent(confirmation -> eventPayload.put("confirmation", confirmation));
            buildMissingAssignmentForm(request.name(), request.arguments(), output)
                    .ifPresent(form -> eventPayload.put("form", form));
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
        } catch (IOException | IllegalStateException e) {
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

    private List<ChatMessage> compactHistoryForRequest(List<ChatMessage> messages, String stage) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        int beforeTokens = estimateTokens(messages);
        if (beforeTokens <= maxContextTokens || messages.size() <= 3) {
            return messages;
        }

        ChatMessage primarySystemPrompt = null;
        List<ChatMessage> body = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (primarySystemPrompt == null && message instanceof SystemMessage) {
                primarySystemPrompt = message;
            } else {
                body.add(message);
            }
        }

        int tailSize = Math.min(Math.max(2, contextTailMessages), body.size());
        if (body.size() <= tailSize) {
            return messages;
        }

        List<ChatMessage> older = new ArrayList<>(body.subList(0, body.size() - tailSize));
        List<ChatMessage> tail = new ArrayList<>(body.subList(body.size() - tailSize, body.size()));
        List<ChatMessage> compacted = buildCompactedMessages(primarySystemPrompt, older, tail);

        while (estimateTokens(compacted) > maxContextTokens && tail.size() > 2) {
            older.add(tail.remove(0));
            compacted = buildCompactedMessages(primarySystemPrompt, older, tail);
        }

        int afterTokens = estimateTokens(compacted);
        log.info("[ContextCompaction] stage={} messages {}->{} tokens~{}->{} olderCompacted={} tailKept={}",
                stage, messages.size(), compacted.size(), beforeTokens, afterTokens, older.size(), tail.size());
        return compacted;
    }

    private List<ChatMessage> buildCompactedMessages(
            ChatMessage primarySystemPrompt,
            List<ChatMessage> older,
            List<ChatMessage> tail) {
        List<ChatMessage> compacted = new ArrayList<>();
        if (primarySystemPrompt != null) {
            compacted.add(primarySystemPrompt);
        }
        if (!older.isEmpty()) {
            compacted.add(SystemMessage.from(buildCompactSummary(older)));
        }
        compacted.addAll(tail);
        return compacted;
    }

    private String buildCompactSummary(List<ChatMessage> olderMessages) {
        StringBuilder summary = new StringBuilder();
        summary.append("[COMPACTED CONVERSATION CONTEXT]\n");
        summary.append("Older messages were compacted to keep the request context small. ");
        summary.append("Use this only as continuity memory; call tools again when current data is needed.\n\n");

        int omitted = 0;
        for (int i = 0; i < olderMessages.size(); i++) {
            ChatMessage message = olderMessages.get(i);
            String line = "- " + compactRole(message) + ": "
                    + compactText(messageText(message), compactMessageMaxChars) + "\n";

            if (summary.length() + line.length() > compactSummaryMaxChars) {
                omitted = olderMessages.size() - i;
                break;
            }
            summary.append(line);
        }

        if (omitted > 0) {
            summary.append("- [").append(omitted).append(" older messages omitted]\n");
        }
        return summary.toString();
    }

    private int estimateTokens(List<ChatMessage> messages) {
        try {
            return tokenCountEstimator.estimateTokenCountInMessages(messages);
        } catch (Exception ex) {
            int total = 0;
            for (ChatMessage message : messages) {
                total += messageText(message).length() / 4;
            }
            return Math.max(1, total);
        }
    }

    private String compactRole(ChatMessage message) {
        if (message instanceof UserMessage) {
            return "User";
        }
        if (message instanceof AiMessage) {
            return "Assistant";
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return "Tool " + toolResult.toolName();
        }
        if (message instanceof SystemMessage systemMessage
                && systemMessage.text() != null
                && systemMessage.text().startsWith("SYSTEM TOOL RESULT")) {
            return "Tool/System";
        }
        if (message instanceof SystemMessage) {
            return "System";
        }
        return message.getClass().getSimpleName();
    }

    private String messageText(ChatMessage message) {
        if (message == null) {
            return "";
        }
        if (message instanceof UserMessage userMessage) {
            return userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
            return stripThinkTags(aiMessage.text());
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return toolResult.text();
        }
        return message.toString();
    }

    private String stripThinkTags(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("<think>[\\s\\S]*?</think>", "").trim();
    }

    private String compactText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        int headLength = Math.max(1, (int) (maxChars * 0.65));
        int tailLength = Math.max(1, maxChars - headLength - 35);
        String head = normalized.substring(0, Math.min(headLength, normalized.length()));
        String tail = normalized.substring(Math.max(0, normalized.length() - tailLength));
        int omitted = Math.max(0, normalized.length() - head.length() - tail.length());
        return head + " ... [" + omitted + " chars compacted] ... " + tail;
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
                String rawData = truncate(toolResult.text(), maxToolResultMemoryChars);

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

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile(
            "<\\s*d?think\\b[^>]*>(.*?)<\\s*/\\s*d?think\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ORPHAN_THINK_TAG_PATTERN = Pattern.compile(
            "</?\\s*d?think\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORD_CONFIRMATION_PATTERN = Pattern.compile(
            "confirmationRequired\\s*=\\s*true.*?actionId\\s*=\\s*([^,\\]\\s]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RECORD_TOOL_NAME_PATTERN = Pattern.compile(
            "toolName\\s*=\\s*([^,\\]\\s]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORD_SUMMARY_PATTERN = Pattern.compile(
            "summary\\s*=\\s*(.*?)(?:,\\s*arguments=|,\\s*preview=|,\\s*expiresAt=|\\])",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private String extractAllThinkBlocks(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }

        Matcher matcher = THINK_BLOCK_PATTERN.matcher(rawResponse);
        List<String> blocks = new ArrayList<>();
        while (matcher.find()) {
            String block = matcher.group(1);
            if (block != null && !block.isBlank()) {
                blocks.add(block.trim());
            }
        }

        if (blocks.isEmpty()) {
            return null;
        }
        return String.join("\n\n", blocks);
    }

    private String stripThinkBlocks(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "";
        }

        String withoutCompleteBlocks = THINK_BLOCK_PATTERN.matcher(rawResponse).replaceAll(" ");
        String withoutOrphanTags = ORPHAN_THINK_TAG_PATTERN.matcher(withoutCompleteBlocks).replaceAll(" ");
        return withoutOrphanTags.trim();
    }

    private Optional<Map<String, Object>> parseConfirmationPayload(String rawToolOutput) {
        if (rawToolOutput == null || rawToolOutput.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(rawToolOutput, MAP_TYPE);
            Object confirmationRequired = parsed.get("confirmationRequired");
            Object actionId = parsed.get("actionId");
            if (Boolean.TRUE.equals(confirmationRequired) && actionId instanceof String actionIdText
                    && !actionIdText.isBlank()) {
                return Optional.of(parsed);
            }
        } catch (Exception ex) {
            log.debug("[HumanInLoop] Tool output is not a confirmation payload: {}", ex.getMessage());
        }
        Matcher recordMatcher = RECORD_CONFIRMATION_PATTERN.matcher(rawToolOutput);
        if (recordMatcher.find()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("confirmationRequired", true);
            fallback.put("actionId", recordMatcher.group(1));
            Matcher toolMatcher = RECORD_TOOL_NAME_PATTERN.matcher(rawToolOutput);
            if (toolMatcher.find()) {
                fallback.put("toolName", toolMatcher.group(1));
            }
            Matcher summaryMatcher = RECORD_SUMMARY_PATTERN.matcher(rawToolOutput);
            if (summaryMatcher.find()) {
                fallback.put("summary", summaryMatcher.group(1).trim());
            }
            return Optional.of(fallback);
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> buildMissingAssignmentForm(String toolName, String rawArguments, String rawToolOutput) {
        if (!"recommendAndAssignTask".equals(toolName) || rawToolOutput == null) {
            return Optional.empty();
        }
        String normalized = rawToolOutput.toLowerCase(Locale.ROOT);
        if (!normalized.contains("missing required skills") && !normalized.contains("missing skills")) {
            return Optional.empty();
        }

        Long taskId = null;
        try {
            Map<String, Object> args = objectMapper.readValue(rawArguments, MAP_TYPE);
            Object rawTaskId = args.get("taskId");
            if (rawTaskId instanceof Number number) {
                taskId = number.longValue();
            } else if (rawTaskId instanceof String text && !text.isBlank()) {
                taskId = Long.valueOf(text);
            }
        } catch (Exception ex) {
            log.debug("[AiForm] Could not parse tool arguments for missing assignment form: {}", ex.getMessage());
        }

        Map<String, Object> difficultyField = new LinkedHashMap<>();
        difficultyField.put("name", "difficulty");
        difficultyField.put("label", "Độ khó (1-10)");
        difficultyField.put("type", "number");
        difficultyField.put("required", true);
        difficultyField.put("min", 1);
        difficultyField.put("max", 10);
        difficultyField.put("placeholder", "5");

        Map<String, Object> skillsField = new LinkedHashMap<>();
        skillsField.put("name", "skills");
        skillsField.put("label", "Kỹ năng yêu cầu");
        skillsField.put("type", "select");
        skillsField.put("required", true);
        skillsField.put("placeholder", "Chọn skill từ hệ thống");

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("title", taskId == null ? "Bổ sung skill để phân công task" : "Bổ sung skill để phân công Task " + taskId);
        form.put("description", "Task chưa có kỹ năng yêu cầu. Chọn skill phù hợp từ danh mục hệ thống rồi tiếp tục phân công.");
        form.put("submitLabel", "Tiếp tục phân công");
        form.put("intent", taskId == null ? "assign_task_missing_skills" : "assign_task_" + taskId);
        form.put("fields", List.of(difficultyField, skillsField));
        return Optional.of(form);
    }

    private String appendTaskPilotBlocks(String responseText, List<Map<String, Object>> toolCallSummaries) {
        if (toolCallSummaries == null || toolCallSummaries.isEmpty()) {
            return responseText;
        }

        List<String> blocks = new ArrayList<>();
        for (Map<String, Object> summary : toolCallSummaries) {
            Object confirmation = summary.get("confirmation");
            if (confirmation instanceof Map<?, ?> confirmationMap) {
                try {
                    blocks.add("```taskpilot-confirm\n"
                            + objectMapper.writeValueAsString(confirmationMap)
                            + "\n```");
                } catch (Exception ex) {
                    log.warn("[HumanInLoop] Failed to serialize pending action metadata: {}", ex.getMessage());
                }
            }

            Object form = summary.get("form");
            if (form instanceof Map<?, ?> formMap) {
                try {
                    blocks.add("```taskpilot-form\n"
                            + objectMapper.writeValueAsString(formMap)
                            + "\n```");
                } catch (Exception ex) {
                    log.warn("[AiForm] Failed to serialize dynamic form metadata: {}", ex.getMessage());
                }
            }
        }

        if (blocks.isEmpty()) {
            return responseText;
        }

        String visibleText = responseText == null ? "" : responseText.trim();
        return (visibleText + "\n\n" + String.join("\n\n", blocks)).trim();
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

    private String abbreviateForLog(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record ToolLoopState(String toolName, int consecutiveCount) {
    }
}
