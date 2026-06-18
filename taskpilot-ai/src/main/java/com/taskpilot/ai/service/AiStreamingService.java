package com.taskpilot.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.entity.ChatMessageEntity.SenderType;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.config.OpenRouterMultiKeyStreamingChatModel;
import com.taskpilot.ai.heuristic.HeuristicConfigProvider;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
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
import java.time.Duration;
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

    /**
     * Essential tools injected when ToolScope.ESSENTIAL is used.
     * These cover the core query + assignment workflow and cost ~1200 tokens
     * instead of ~3356 for the full set.
     */
    private static final List<String> ESSENTIAL_TOOL_NAMES = List.of(
            "getMyProjects",
            "getMyTasks",
            "getProjectStatus",
            "getTaskDetails",
            "getTasksByProject",
            "getUnassignedTasksByProject",
            "getMemberWorkload",
            "getProjectMembers",
            "recommendAssignmentCandidates",
            "recommendAndAssignTask",
            "assignTaskToMember",
            "assignTaskToMemberByName",
            "confirmPendingAction",
            "cancelPendingAction",
            "searchSystemSkills"
    );

    /** GitHub Models hard limit; Gemini/Groq have much larger budgets. */
    private static final int GITHUB_MODELS_MAX_TOKENS = 8000;
    private static final int LARGE_CONTEXT_MAX_TOKENS = 128_000;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SmartRoutingService routingService;
    private final AiLogService aiLogService;
    private final ChatStreamStatusService chatStreamStatusService;
    private final SessionChatMemoryService sessionChatMemoryService;
    private final ToolCallingRegistryService toolCallingRegistryService;
    private final HeuristicConfigProvider heuristicConfigProvider;
    private final ThinkingNarratorService thinkingNarratorService;
    private final com.taskpilot.ai.gatekeeper.GatekeeperService gatekeeperService;
    private final TokenCountEstimator tokenCountEstimator;
    private final ObjectMapper objectMapper;
    private final UserProfilePort userProfilePort;

    @Value("${ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${ai.gemini.timeout-seconds:20}")
    private int geminiTimeoutSeconds;

    @Value("${ai.chat.max-output-tokens:3500}")
    private int maxOutputTokens;

    @Value("${ai.chat.stream-first-response-timeout-seconds:60}")
    private int streamFirstResponseTimeoutSeconds;

    @Value("${ai.chat.text-only-first-response-timeout-seconds:25}")
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
            You are the Backend Executor Agent of the TaskPilot system. Your SOLE purpose is to execute system instructions by calling the appropriate tools.
            YOU MUST NOT answer the user's question directly with text. YOU MUST ONLY call tools to fetch data or perform actions.
            If the instruction tells you to fetch data or perform an action, you MUST call the relevant tool(s). Do NOT generate conversational text.

            [CURRENT SYSTEM CONTEXT]
            - Today's Date: {{current_date}}
            - Current Assignment Mode: {{current_mode}}
            - Current User: {{current_user_name}} (ID: {{current_user_id}})

            [TASKPILOT TOOL WORKFLOW RULES]
            - If the user asks you to perform an action but the required tool is not available, respond exactly with:
              MISSING_TOOL: <short reason>
            - The user frequently uses Vietnamese shorthand, abbreviations, and chat slang (e.g., "ch" = chưa, "tb" = thông báo, "da" = dự án, "nv" = nhiệm vụ/nhân viên, "đc" = được, "sl" = số lượng). You MUST actively infer the full meaning of any unrecognized acronyms or abbreviations based on the surrounding context. Never assume an unrecognized abbreviation is a typo; always try to interpret it as a Vietnamese abbreviation first. For task assignment questions, interpret "ch" as unassigned/not assigned.
            - If the user names a specific assignee (for example "cho Julia Design", "gán cho Ian",
              "assign task 68 to Julia"), the user's explicit assignee overrides the recommendation algorithm.
              If only the assignee changes, call assignTaskToMemberByName or assignTaskToMember after resolving
              the member. If the same user request or active context also includes other task field changes such
              as dueDate, call patchTask with patchData containing every changed field, e.g.
              {"dueDate":"2026-07-31","assigneeId":9}. Do NOT call recommendAndAssignTask, because
              recommendAndAssignTask always picks the top ranked candidate.
            - If the user asks which tasks are not assigned yet in a project, call getUnassignedTasksByProject.
              Do not answer from the full task list unless the unassigned-only tool is unavailable.
            - If the user asks for overdue tasks, use getTasksByProject and filter the results yourself comparing dueDate to Today's Date. Do NOT invent or assume a getOverdueTasks tool exists.
            - If the user asks for unassigned tasks in the project that contains a task ID (for example
              "du an co chua task 67"), first call getTaskDetails(taskId) to resolve projectId, then call
              getUnassignedTasksByProject(projectId).
            - If the user asks to recommend suitable assignees, "rcm", "gợi ý", or asks to reassign to
              "người khác" without naming the final assignee, call recommendTaskAssignmentCandidates for each
              concrete task. This is recommendation-only and MUST NOT create a pending write confirmation.
              After showing recommendations, wait for the user to pick a candidate.
            - If the user asks to recommend a suitable assignee and also apply the assignment in the same request
              (for example "gợi ý rồi gán luôn"), call recommendAndAssignTask for each concrete task. This is a
              real data write action.
            - If the user asks for recommendations for a concrete task ID, prefer recommendTaskAssignmentCandidates
              over recommendAssignmentCandidates because it reads the task's project, skills, difficulty, and
              current assignee automatically. If the user says "người khác", "recommend someone else", "đổi người
              làm", or "reassign", call recommendTaskAssignmentCandidates with excludeCurrentAssignee=true.
              If the user asks to compare specific people for a task, pass those names or IDs in includeMemberNames
              or includeMemberIds so the recommendation compares only those people.
            - If the user asks to change/reassign the assignee but does not name a new member, do not ask for a
              member ID first. Treat this as a request for alternatives: recommend candidates with
              recommendTaskAssignmentCandidates and exclude the current assignee. Phrases like "đổi người làm",
              "đổi người phụ trách", or "reassign" are NOT the same as "assign to me" unless the user explicitly
              says "cho tôi", "gán cho tôi", "to me", or uses the current user's name.
            - For entity updates where only some fields change, prefer the matching patch tool: patchTask,
              patchProject, patchSprint, patchTaskComment, patchSystemSkill, or patchMySkill. Pass a patchJson object containing
              only the fields to change. Example: {"dueDate":"2026-06-30","assigneeId":2}.
              Do not ask for title, description, status, priority, position, difficulty, labels, required skills,
              assignee, start date, or due date unless that field is actually missing and needed for the requested
              change.
            - If task skills or difficulty are missing, ask for only the missing fields. The frontend may provide
              those fields as a structured "Task assignment requirements form"; use that structured data directly.
              When the user provides missing task skills for recommendation-only, call recommendTaskAssignmentCandidates
              with the provided skills/difficulty and do not create a write confirmation. When the user explicitly
              asked to assign immediately, call recommendAndAssignTask with those skills and difficulty so the
              pending confirmation saves the task skills and assigns the task together. If the user only wants to
              update task skills, call updateTaskRequiredSkills.
            - Multi-step user requests are allowed. However, to minimize processing time, you MUST call multiple tools IN PARALLEL whenever possible. If you need data from different sources (e.g. projects, tasks, and members), call all the relevant read tools simultaneously in a single turn instead of waiting for one to finish before calling the next. Only use sequential rounds when a subsequent tool call strictly depends on the result of a previous one.
            - PARALLEL CALLING EXAMPLE: If the user says "lấy danh sách dự án và kiểm tra task hôm nay", you MUST:
              Step 1: Call queryProjects() to get projects. After receiving the result with projectId:
              Step 2: Call queryTasks(projectId=X, assigneeId=currentUserId) AND queryProjectMembers(projectId=X) simultaneously in the SAME turn.
              Do NOT call them one-by-one in separate turns.
            - CRITICAL TOOL FORMAT: You MUST use native JSON function calling. DO NOT output pseudo-code like <tool_call> toolName(...) </tool_call>. If native calling fails or you must force a tool call via text, you MUST output EXACTLY this JSON format and nothing else:
              ```json
              { "tool": "toolName", "arguments": { "arg1": "value", "arg2": true } }
              ```
            - Any create/update/delete/assignment tool may return confirmationRequired=true instead of writing data.
              In that case, tell the user exactly what will change and wait for a final confirmation. Do not claim
              the change has been applied until confirmPendingAction returns a success result.
            - IF A TOOL RETURNS "Pending action not found or expired", DO NOT call confirmPendingAction again! Inform the user that the action expired.
            - TO FETCH DATA: You MUST call the appropriate read tools (e.g. `queryProjects`, `queryTasks`). NEVER assume you have the data or that the user has no data unless you have explicitly called a read tool and received an empty result.
            - When you need additional structured information from the user, include a fenced `taskpilot-form`
              JSON block so the frontend can render an interactive form.
              CRITICAL FORM RULES:
              1. DO NOT tell the user to run tools. If a user asks to "tạo task" (create task), you MUST immediately call `queryProjects` to fetch their projects BEFORE responding with a form. Do NOT generate a `taskpilot-form` in the same turn if you haven't called the tool yet!
              2. For ID fields (projectId, sprintId, assigneeId), use `type: "number"`. DO NOT provide an `options` array; the frontend will automatically fetch and convert them to dropdowns. However, you MUST still call `queryProjects` first to know if the user even has projects.
              3. For lists of IDs or multiple selections (e.g., requiredSkillIds, labelIds), you MUST use `type: "multiselect"` instead of "select" and provide an `options` array.
              4. Only ask for fields that are truly missing.
              5. For `createTask`, ONLY `projectId` and `title` are required. `sprintId`, `difficultyLevel`, `startDate`, `dueDate`, etc. MUST have `required: false`.
              
              Example of a good form:
              ```taskpilot-form
              {"title":"Tao task moi","description":"Vui long chon project va nhap tieu de","submitLabel":"Tao task","intent":"continue_previous_request","fields":[{"name":"projectId","label":"Project","type":"number","required":true},{"name":"title","label":"Tieu de","type":"text","required":true},{"name":"sprintId","label":"Sprint","type":"number","required":false}]}
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
            2. ALWAYS write your step-by-step thinking process in Vietnamese enclosed in <think>...</think> tags at the very beginning of your response. Explain what tools you need to call and why, or how you formulate your final answer. Do NOT omit these tags.
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

        String systemPrompt = buildSystemPrompt(userId);
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
        boolean requiresTools = decision.requiresTools();

        chatStreamStatusService.updatePhase(sessionId, effectiveClientMessageId, Phase.THINKING, modelName, null, null);

        String finalClientMessageId = effectiveClientMessageId;
        executor.submit(() -> {
            try {
                safeSend(emitter, "model", modelName, null);
                safeSend(emitter, "phase", Phase.THINKING.name(), null);
                doStream(emitter, emitterCompleted, session, sessionId, userId, userInput, requestHistory, systemPrompt,
                        selectedModel, modelName, startTime, false, finalClientMessageId, requiresAHP, requiresTools, 0);
            } catch (Exception e) {
                log.error("[SSE] Exception in stream thread for session {}: {}", sessionId, e.getMessage(), e);
                chatStreamStatusService.updatePhase(sessionId, finalClientMessageId, Phase.FAILED, modelName, null, e.getMessage());
                safeSend(emitter, "phase", Phase.FAILED.name(), null);
                safeSend(emitter, "error", java.util.Map.of("error", e.getMessage(), "type", "generation_failed"), org.springframework.http.MediaType.APPLICATION_JSON);
                safeComplete(emitter, emitterCompleted);
            }
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
            boolean requiresAHP,
            boolean requiresTools,
            int retryCount) {
        doStreamWithKeyAttempts(
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
                requiresAHP,
                requiresTools,
                retryCount,
                1);
    }

    private void doStreamWithKeyAttempts(SseEmitter emitter,
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

        // Start thinking block immediately to reduce perceived latency
        String initialStep = getInitialStep(userInput);
        safeSend(emitter, "token", java.util.Map.of("token", "<think>\n" + initialStep + "\n\n"), MediaType.APPLICATION_JSON);

        final List<String> periodicSteps = getPeriodicSteps(userInput);
        final java.util.concurrent.atomic.AtomicInteger stepIndex = new java.util.concurrent.atomic.AtomicInteger(0);
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
                safeSend(emitter, "token", java.util.Map.of("token", stepText), MediaType.APPLICATION_JSON);
            }
        }, 10, 10, TimeUnit.SECONDS);

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
                requiresTools,
                null,
                0,
                toolCallSummaries,
                toolNames,
                retryCount,
                initialModelKeyAttempts);
    }

    private String getInitialStep(String userInput) {
        if (userInput == null) {
            userInput = "";
        }
        String inputLower = userInput.toLowerCase(Locale.ROOT);

        boolean isNotification = inputLower.contains("thông báo") || inputLower.contains("thong bao") || inputLower.contains("tb") 
                || inputLower.contains("tin nhắn") || inputLower.contains("tin nhan") || inputLower.contains("chưa đọc") 
                || inputLower.contains("chua doc") || inputLower.contains("unread");

        boolean isAssignment = inputLower.contains("phân công") || inputLower.contains("phan cong") 
                || inputLower.contains("gợi ý") || inputLower.contains("goi y") || inputLower.contains("rcm") 
                || inputLower.contains("gán") || inputLower.contains("gan") || inputLower.contains("assign") 
                || inputLower.contains("kỹ năng") || inputLower.contains("ky nang") || inputLower.contains("skill") 
                || inputLower.contains("ahp") || inputLower.contains("đề xuất") || inputLower.contains("de xuat");

        boolean isTask = inputLower.contains("task") || inputLower.contains("nhiệm vụ") || inputLower.contains("nhiem vu") 
                || inputLower.contains("công việc") || inputLower.contains("cong viec") || inputLower.contains("nv") 
                || inputLower.contains("việc") || inputLower.contains("viec") || inputLower.contains("deadline") 
                || inputLower.contains("hạn") || inputLower.contains("han") || inputLower.contains("trễ") 
                || inputLower.contains("tre") || inputLower.contains("to-do") || inputLower.contains("todo");

        boolean isProject = inputLower.contains("dự án") || inputLower.contains("du an") || inputLower.contains("da") 
                || inputLower.contains("sprint") || inputLower.contains("mốc thời gian") || inputLower.contains("moc thoi gian") 
                || inputLower.contains("tiến độ") || inputLower.contains("tien do") || inputLower.contains("tài liệu") 
                || inputLower.contains("tai lieu") || inputLower.contains("tập tin") || inputLower.contains("tap tin")
                || inputLower.contains("thành viên") || inputLower.contains("thanh vien") || inputLower.contains("người tham gia")
                || inputLower.contains("nguoi tham gia");

        int matchedGroups = 0;
        if (isNotification) matchedGroups++;
        if (isAssignment) matchedGroups++;
        if (isTask) matchedGroups++;
        if (isProject) matchedGroups++;

        if (matchedGroups >= 2) {
            return "Phân tích các yêu cầu tổng hợp liên quan đến công việc và dự án...";
        }

        if (isNotification) {
            return "Phân tích yêu cầu về thông báo...";
        }

        if (isAssignment) {
            return "Phân tích yêu cầu về nhân sự và đề xuất phân công...";
        }

        if (isTask) {
            return "Phân tích yêu cầu về nhiệm vụ và công việc...";
        }

        if (isProject) {
            return "Phân tích yêu cầu về dự án và thành viên...";
        }

        return "Phân tích yêu cầu của bạn...";
    }

    private List<String> getPeriodicSteps(String userInput) {
        if (userInput == null) {
            userInput = "";
        }
        String inputLower = userInput.toLowerCase(Locale.ROOT);

        boolean isNotification = inputLower.contains("thông báo") || inputLower.contains("thong bao") || inputLower.contains("tb") 
                || inputLower.contains("tin nhắn") || inputLower.contains("tin nhan") || inputLower.contains("chưa đọc") 
                || inputLower.contains("chua doc") || inputLower.contains("unread");

        boolean isAssignment = inputLower.contains("phân công") || inputLower.contains("phan cong") 
                || inputLower.contains("gợi ý") || inputLower.contains("goi y") || inputLower.contains("rcm") 
                || inputLower.contains("gán") || inputLower.contains("gan") || inputLower.contains("assign") 
                || inputLower.contains("kỹ năng") || inputLower.contains("ky nang") || inputLower.contains("skill") 
                || inputLower.contains("ahp") || inputLower.contains("đề xuất") || inputLower.contains("de xuat");

        boolean isTask = inputLower.contains("task") || inputLower.contains("nhiệm vụ") || inputLower.contains("nhiem vu") 
                || inputLower.contains("công việc") || inputLower.contains("cong viec") || inputLower.contains("nv") 
                || inputLower.contains("việc") || inputLower.contains("viec") || inputLower.contains("deadline") 
                || inputLower.contains("hạn") || inputLower.contains("han") || inputLower.contains("trễ") 
                || inputLower.contains("tre") || inputLower.contains("to-do") || inputLower.contains("todo");

        boolean isProject = inputLower.contains("dự án") || inputLower.contains("du an") || inputLower.contains("da") 
                || inputLower.contains("sprint") || inputLower.contains("mốc thời gian") || inputLower.contains("moc thoi gian") 
                || inputLower.contains("tiến độ") || inputLower.contains("tien do") || inputLower.contains("tài liệu") 
                || inputLower.contains("tai lieu") || inputLower.contains("tập tin") || inputLower.contains("tap tin")
                || inputLower.contains("thành viên") || inputLower.contains("thanh vien") || inputLower.contains("người tham gia")
                || inputLower.contains("nguoi tham gia");

        int matchedGroups = 0;
        if (isNotification) matchedGroups++;
        if (isAssignment) matchedGroups++;
        if (isTask) matchedGroups++;
        if (isProject) matchedGroups++;

        if (matchedGroups >= 2) {
            return List.of(
                "Kết nối cơ sở dữ liệu kiểm tra thông tin liên quan...",
                "Truy xuất danh sách dự án và thành viên tương ứng...",
                "Rà soát danh sách nhiệm vụ và đối chiếu thời hạn (deadline)...",
                "Đối chiếu thông tin và xử lý dữ liệu tổng hợp...",
                "Chuẩn bị thông tin phản hồi chi tiết..."
            );
        }

        if (isNotification) {
            return List.of(
                "Kết nối dịch vụ thông báo để kiểm tra dữ liệu...",
                "Truy xuất danh sách các thông báo chưa đọc...",
                "Phân tích nội dung và sắp xếp thông báo theo thời gian...",
                "Cập nhật trạng thái hiển thị các thông báo mới...",
                "Tổng hợp thông tin thông báo chi tiết..."
            );
        }

        if (isAssignment) {
            return List.of(
                "Truy xuất thông tin kỹ năng và khối lượng công việc của thành viên...",
                "Đánh giá yêu cầu kỹ năng và độ khó của nhiệm vụ...",
                "Áp dụng mô hình phân tích AHP để đánh giá độ phù hợp...",
                "So sánh hiệu suất và mức độ sẵn sàng của các thành viên...",
                "Tối ưu hóa phương án đề xuất phân công công việc..."
            );
        }

        if (isTask) {
            return List.of(
                "Kiểm tra danh sách nhiệm vụ được giao cho bạn...",
                "Rà soát thời hạn (deadline) và mức độ ưu tiên của các công việc...",
                "Kiểm tra các task đến hạn hoặc bị trễ hạn...",
                "Phân tích trạng thái tiến độ các nhiệm vụ hiện tại...",
                "Tổng hợp thông tin công việc chi tiết..."
            );
        }

        if (isProject) {
            return List.of(
                "Truy xuất danh sách dự án bạn tham gia...",
                "Tải thông tin chi tiết và tiến độ các dự án...",
                "Kiểm tra danh sách thành viên trong các dự án liên quan...",
                "Rà soát các mốc thời gian và trạng thái hoạt động của dự án...",
                "Tổng hợp thông tin dự án chi tiết..."
            );
        }

        return List.of(
            "Kết nối cơ sở dữ liệu để kiểm tra thông tin...",
            "Truy xuất các thông tin liên quan từ cơ sở dữ liệu...",
            "Tiến hành đối chiếu và kiểm tra tính toàn vẹn của dữ liệu...",
            "Lập phương án xử lý tối ưu cho yêu cầu...",
            "Chuẩn bị phản hồi chi tiết..."
        );
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
            boolean requiresTools,
            String lastToolName,
            int consecutiveToolExecutions,
            List<Map<String, Object>> toolCallSummaries,
            LinkedHashSet<String> toolNames,
            int retryCount,
            int modelKeyAttempts) {
        final String resolvedUserInput = userInput;

        List<ChatMessage> sanitizedHistory = cleanAndAlternateRoles(
                compactHistoryForRequest(
                        sanitizeHistoryForTools(history),
                        "tool-round-" + toolRound),
                routingService.isGeminiModel(model));
        // Smart tool injection: only send tools that the routing decision actually needs.
        // FULL  = all tools (~3356 tokens) — only for Gemini with large context
        // ESSENTIAL = core query/assignment tools (~1200 tokens) — for GitHub Models / Groq
        // NONE  = no tools at all — for text-only / light responses
        List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecs = null;
        ToolChoice toolChoice = null;

        if (requiresAHP) {
            if (toolRound == 0) {
                // Round 0: Force AHP tool call — only inject the one AHP tool spec
                List<dev.langchain4j.agent.tool.ToolSpecification> ahpOnly = toolCallingRegistryService
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
                forceTextOnlyResponse(
                        emitter, emitterCompleted, session, sessionId, userId, userInput,
                        history, systemPrompt, model, modelName, startTime,
                        isFallbackAttempt, clientMessageId, fullResponse,
                        clientDisconnected, generatingMarked, requiresAHP,
                        toolCallSummaries, toolNames,
                        "Based on the tool data already provided in the context above, provide your final recommendation now. Do not call any tools.");
                return;
            }
        } else if (requiresTools) {
            boolean expanded = (retryCount > 0);
            int maxTools = expanded ? 40 : 30;
            List<String> dynamicToolNames = toolCallingRegistryService.selectToolNames(userInput, maxTools, expanded);
            toolSpecs = toolCallingRegistryService.toolSpecificationsByNames(dynamicToolNames);
            log.info("[streamRound] Dynamic tools round={} expanded={} -> injecting {} tool specs", toolRound, expanded, toolSpecs.size());
        } else {
            log.debug("[streamRound] requiresTools=false -> skipping tool specs entirely");
        }

        // Dynamic max output tokens based on model capacity and current payload
        int modelBudget = routingService.supportsLargeContextAndTools(model)
                ? LARGE_CONTEXT_MAX_TOKENS : GITHUB_MODELS_MAX_TOKENS;
        int historyTokens = estimateTokens(sanitizedHistory);
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

        // Last-resort guard: if total would still exceed budget, drop tools
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

                // === DEBUG: log token breakdown before sending ===
                {
                    int totalEstimate = historyTokens + toolSpecTokens + resolvedMaxOutput;
                    log.info("[TokenDebug] session={} round={} model={} retryCount={} | msgs={} history~{} tools~{} maxOut={} budget={} TOTAL~{}",
                            sessionId, toolRound, modelName, retryCount,
                            sanitizedHistory.size(), historyTokens, toolSpecTokens, resolvedMaxOutput, modelBudget, totalEstimate);
                    if (log.isDebugEnabled()) {
                        for (int i = 0; i < sanitizedHistory.size(); i++) {
                            ChatMessage m = sanitizedHistory.get(i);
                            int toks = tokenCountEstimator.estimateTokenCountInMessage(m);
                            String preview = messageText(m);
                            preview = preview != null && preview.length() > 80 ? preview.substring(0, 80) + "..." : preview;
                            log.debug("[TokenDebug]   msg[{}] type={} tokens~{} preview='{}'",
                                    i, m.getClass().getSimpleName(), toks, preview);
                        }
                    }
                }
                // === END DEBUG ===

                final AtomicBoolean roundClosed = new AtomicBoolean(false);
                final AtomicBoolean firstModelSignalReceived = new AtomicBoolean(false);
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
                                requiresAHP,
                                requiresTools,
                                retryCount,
                                modelKeyAttempts);
                    }
                }, Math.max(1, streamFirstResponseTimeoutSeconds), TimeUnit.SECONDS);

                final StringBuilder thinkingBuffer = new StringBuilder();
                final AtomicBoolean insideThink = new AtomicBoolean(false);

                StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (roundClosed.get()) {
                            return;
                        }
                        firstModelSignalReceived.set(true);
                        fullResponse.append(partialResponse);

                        boolean isExecutor = requiresTools && !routingService.isGeminiModel(model);

                        boolean hasThinkOpen = partialResponse.contains("<think>");
                        boolean hasThinkClose = partialResponse.contains("</think>");

                        if (hasThinkOpen) {
                            insideThink.set(true);
                        }
                        if (insideThink.get()) {
                            thinkingBuffer.append(partialResponse);
                        }

                        if (!isExecutor && !generatingMarked.get()) {
                            if (hasThinkClose) {
                                insideThink.set(false);
                                safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
                                generatingMarked.set(true);
                                chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.GENERATING, modelName, null, null);
                                safeSend(emitter, "phase", Phase.GENERATING.name(), null);
                            } else if (!insideThink.get() && !hasThinkOpen && !partialResponse.trim().isEmpty()) {
                                safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
                                generatingMarked.set(true);
                                chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.GENERATING, modelName, null, null);
                                safeSend(emitter, "phase", Phase.GENERATING.name(), null);
                            }
                        }

                        if (hasThinkClose) {
                            insideThink.set(false);
                        }

                        if (!clientDisconnected.get()) {
                            boolean shouldStream = !isExecutor || insideThink.get() || hasThinkOpen || hasThinkClose;
                            if (shouldStream) {
                                String clientToken = partialResponse.replace("<think>", "").replace("</think>", "");
                                if (!clientToken.isEmpty()) {
                                    if (!safeSend(emitter, "token", Map.of("token", clientToken), MediaType.APPLICATION_JSON)) {
                                        clientDisconnected.set(true);
                                    }
                                }
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

                        if (aiMessage != null && aiMessage.text() != null && !aiMessage.hasToolExecutionRequests()) {
                            String text = aiMessage.text().trim();
                            List<ToolExecutionRequest> extractedRequests = new ArrayList<>();
                            
                            // Fallback for models (like Groq) that hallucinate tool calls as raw JSON text
                            if (text.contains("\"tool\"") || text.contains("```json")) {
                                // Try to match ```json { "tool": "name", "arguments": {} } ```
                                Matcher m = Pattern.compile("(?s)```json\\s*(\\{\\s*\"tool\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?\\})\\s*\\})\\s*```").matcher(text);
                                while (m.find()) {
                                    extractedRequests.add(ToolExecutionRequest.builder()
                                        .id(UUID.randomUUID().toString())
                                        .name(m.group(2))
                                        .arguments(m.group(3))
                                        .build());
                                }
                                
                                // Try to match raw {"tool":"name","arguments":{}} anywhere in the text
                                if (extractedRequests.isEmpty()) {
                                    Matcher m2 = Pattern.compile("(?s)(\\{\\s*\"tool\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?\\})\\s*\\})").matcher(text);
                                    while (m2.find()) {
                                        extractedRequests.add(ToolExecutionRequest.builder()
                                            .id(UUID.randomUUID().toString())
                                            .name(m2.group(2))
                                            .arguments(m2.group(3))
                                            .build());
                                    }
                                }
                            }

                            // Fallback for XML-like tool calls
                            if (extractedRequests.isEmpty() && text.contains("<tool_call>")) {
                                Matcher toolCallMatcher = Pattern.compile("(?s)<tool_call>(.*?)</tool_call>").matcher(text);
                                while (toolCallMatcher.find()) {
                                    String content = toolCallMatcher.group(1);
                                    
                                    // Parse function name
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
                                        
                                        String jsonArgs = "{";
                                        if (!params.isEmpty()) {
                                            List<String> jsonPairs = new ArrayList<>();
                                            for (Map.Entry<String, String> entry : params.entrySet()) {
                                                String k = entry.getKey();
                                                String v = entry.getValue();
                                                if (v.equalsIgnoreCase("null") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false") || v.matches("-?\\d+")) {
                                                    jsonPairs.add("\"" + k + "\":" + v.toLowerCase());
                                                } else {
                                                    jsonPairs.add("\"" + k + "\":\"" + v.replace("\"", "\\\"") + "\"");
                                                }
                                            }
                                            jsonArgs += String.join(",", jsonPairs);
                                        }
                                        jsonArgs += "}";
                                        
                                        extractedRequests.add(ToolExecutionRequest.builder()
                                            .id(UUID.randomUUID().toString())
                                            .name(tName)
                                            .arguments(jsonArgs)
                                            .build());
                                    }
                                }
                            }

                            // Fallback for OpenRouter Gemma models leaking <tool_call> tags
                            if (extractedRequests.isEmpty() && text.contains("<tool_call>")) {
                                Matcher m = Pattern.compile("(?s)<tool_call>\\s*([a-zA-Z0-9_]+)[({](.*?)[)}]\\s*</tool_call>").matcher(text);
                                while (m.find()) {
                                    String tName = m.group(1);
                                    String argsRaw = m.group(2);
                                    String jsonArgs = "{";
                                    if (!argsRaw.isBlank()) {
                                        String[] pairs = argsRaw.split(",");
                                        List<String> jsonPairs = new ArrayList<>();
                                        for (String pair : pairs) {
                                            String[] kv = pair.split("[=:]", 2);
                                            if (kv.length == 2) {
                                                String k = kv[0].trim();
                                                String v = kv[1].trim();
                                                if (v.equalsIgnoreCase("null") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false") || v.matches("-?\\d+")) {
                                                    jsonPairs.add("\"" + k + "\":" + v);
                                                } else {
                                                    jsonPairs.add("\"" + k + "\":\"" + v.replace("\"", "\\\"") + "\"");
                                                }
                                            }
                                        }
                                        jsonArgs += String.join(",", jsonPairs);
                                    }
                                    jsonArgs += "}";
                                    extractedRequests.add(ToolExecutionRequest.builder()
                                        .id(UUID.randomUUID().toString())
                                        .name(tName)
                                        .arguments(jsonArgs)
                                        .build());
                                }
                            }

                            if (!extractedRequests.isEmpty()) {
                                log.warn("[AiChat] Recovered hallucinated tool calls from text!");
                                String cleanedText = text.replaceAll("(?s)```json\\s*\\{.*?\\}\\s*```", "").trim();
                                cleanedText = cleanedText.replaceAll("(?s)<tool_call>.*?</tool_call>", "").trim();
                                cleanedText = cleanedText.replaceAll("(?s)^\\s*\\{.*?\\}\\s*$", "").trim();
                                if (cleanedText.isBlank()) {
                                    cleanedText = "Đang kết nối hệ thống để gọi công cụ...";
                                }
                                // Add <think> tags around the cleaned text so the UI knows it's reasoning
                                if (!cleanedText.contains("<think>")) {
                                    cleanedText = "<think>\n" + cleanedText + "\n</think>";
                                }
                                safeSend(emitter, "token", java.util.Map.of("token", cleanedText), org.springframework.http.MediaType.APPLICATION_JSON);
                                aiMessage = dev.langchain4j.data.message.AiMessage.from(cleanedText, extractedRequests);
                            }
                            
                            boolean explicitMissingTool = text.startsWith("MISSING_TOOL:");
                            
                            if (explicitMissingTool && retryCount > 0) {
                                aiMessage = dev.langchain4j.data.message.AiMessage.from("Hệ thống hiện chưa có công cụ phù hợp để thực hiện thao tác này.");
                            } else {
                                boolean regexMissingTool = text.matches("(?is).*(không có công cụ|không tìm thấy công cụ|không thể thực hiện|không có quyền truy cập|not provided with a tool|missing tool|cannot perform|cannot access).*");
                                boolean executionExpectedButNoToolCalled = requiresTools && !text.matches("(?is).*(vui lòng|bạn có muốn|cung cấp thêm|task là gì).*");
                                
                                if ((explicitMissingTool || regexMissingTool || executionExpectedButNoToolCalled) && retryCount == 0) {
                                    log.warn("[Fallback] Missing tool detected. Retrying with expanded context and tools enabled...");
                                    doStream(emitter, emitterCompleted, session, sessionId, userId, userInput, history, systemPrompt, model, modelName, startTime, isFallbackAttempt, clientMessageId, requiresAHP, true, 1);
                                    return;
                                }
                            }
                        }

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
                                requiresTools,
                                nextToolState.toolName(),
                                nextToolState.consecutiveCount(),
                                toolCallSummaries,
                                toolNames,
                                retryCount,
                                modelKeyAttempts);
                    }
                    return;
                }

                long durationMs = System.currentTimeMillis() - startTime;
                String rawResponseText = fullResponse.toString();
                if ((rawResponseText == null || rawResponseText.isBlank()) && aiMessage != null && aiMessage.text() != null) {
                    rawResponseText = aiMessage.text();
                }

                if (requiresTools && !routingService.isGeminiModel(model)) {
                    log.info("[Multi-Agent] Chặng 3: Executor finished. Forwarding result to Communicator (Gemma) for streaming...");
                    try {
                        emitter.send(SseEmitter.event().id(clientMessageId).name("status").data("🟢 Hoàn tất truy xuất! Đang tổng hợp kết quả..."));
                    } catch (Exception ignored) {}
                    
                    safeSend(emitter, "token", java.util.Map.of("token", "\n\n"), org.springframework.http.MediaType.APPLICATION_JSON);
                    
                    String promptForGemma = "Đây là kết quả hệ thống vừa truy xuất từ Database. Hãy trả lời trực tiếp cho người dùng dựa trên thông tin này một cách thân thiện, ngắn gọn và chính xác. TUYỆT ĐỐI KHÔNG sinh ra thẻ <think> hay bất kỳ quá trình suy nghĩ nào khác. Trả lời trực tiếp vào nội dung câu hỏi: \n" + rawResponseText;
                    
                    forceTextOnlyResponse(
                            emitter, emitterCompleted, session, sessionId, userId, userInput,
                            history, systemPrompt, routingService.getReasoningTextModel(), routingService.getModelName(routingService.getReasoningTextModel()), startTime,
                            isFallbackAttempt, clientMessageId, fullResponse,
                            clientDisconnected, generatingMarked, requiresAHP,
                            toolCallSummaries, toolNames,
                            promptForGemma);
                    return;
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

                if (generatingMarked.compareAndSet(false, true)) {
                    safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
                }

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

                if (hasRemainingOpenRouterKeys(model, modelKeyAttempts)) {
                    int nextAttempt = modelKeyAttempts + 1;
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.THINKING, modelName, null, null);
                    safeSend(emitter, "model",
                            modelName + " (next OpenRouter key " + nextAttempt + "/" + openRouterKeyCount(model) + ")",
                            null);
                    safeSend(emitter, "phase", Phase.THINKING.name(), null);
                    doStreamWithKeyAttempts(emitter, emitterCompleted, session, sessionId, userId, userInput,
                            history, systemPrompt, model, modelName, startTime,
                            isFallbackAttempt, clientMessageId, requiresAHP, requiresTools, retryCount, nextAttempt);
                    return;
                }

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
                    doStreamWithKeyAttempts(emitter, emitterCompleted, session, sessionId, userId, userInput, history, systemPrompt, fallback,
                            fallbackName, startTime, !routingService.hasStreamingFallbackAfter(fallback), clientMessageId, requiresAHP, requiresTools, retryCount, 1);
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
        };

        if (routingService.isGeminiModel(model) && toolSpecs != null && !toolSpecs.isEmpty()) {
            log.info("[GeminiToolFix] Using non-streaming Gemini model to prevent SSE hang: {}", modelName);
            executor.submit(() -> {
                try {
                    ChatModel nonStreamingModel = GoogleAiGeminiChatModel.builder()
                            .apiKey(geminiApiKey)
                            .modelName(modelName)
                            .temperature(0.3)
                            .timeout(Duration.ofSeconds(geminiTimeoutSeconds))
                            .logRequestsAndResponses(true)
                            .build();

                    ChatResponse response = nonStreamingModel.chat(request);
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
        String message = "Model did not produce a first streaming response within "
                + streamFirstResponseTimeoutSeconds + "s";
        log.warn("[SSE] {} for session {} using model {}", message, sessionId, modelName);

        if (hasRemainingOpenRouterKeys(model, initialModelKeyAttempts)) {
            int nextAttempt = initialModelKeyAttempts + 1;
            chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                    Phase.THINKING, modelName, null, null);
            safeSend(emitter, "model",
                    modelName + " (next OpenRouter key " + nextAttempt + "/" + openRouterKeyCount(model) + ")",
                    null);
            safeSend(emitter, "phase", Phase.THINKING.name(), null);
            doStreamWithKeyAttempts(emitter, emitterCompleted, session, sessionId, userId, userInput,
                    history, systemPrompt, model, modelName, startTime,
                    isFallbackAttempt, clientMessageId, requiresAHP, requiresTools, retryCount, nextAttempt);
            return;
        }

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
                doStreamWithKeyAttempts(emitter, emitterCompleted, session, sessionId, userId, userInput,
                        history, systemPrompt, fallback, fallbackName, startTime,
                        !routingService.hasStreamingFallbackAfter(fallback), clientMessageId, requiresAHP, requiresTools, retryCount, 1);
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

        // Tool data is already in the prompt at this point. Use the fast text-only
        // finalizer instead of a reasoning/tool model so the UI gets a real answer quickly.
        StreamingChatModel textModel = model;
        String textModelName = modelName;
        log.info("[ForceTextOnly] Using text-only finalizer {} for session {}",
                textModelName, sessionId);

        List<ChatMessage> textOnlyHistory = new ArrayList<>(sanitizeHistoryForTools(history));
        textOnlyHistory.add(SystemMessage.from(guardrailInstruction));
        textOnlyHistory = new ArrayList<>(cleanAndAlternateRoles(
                compactHistoryForRequest(textOnlyHistory, "text-only"),
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
                        null,
                        generatingMarked);
                safeComplete(emitter, emitterCompleted);
            }
        }, Math.max(1, textOnlyFirstResponseTimeoutSeconds), TimeUnit.SECONDS);

        final AtomicBoolean insideLlmThink = new AtomicBoolean(false);
        final StringBuilder filterBuffer = new StringBuilder();

        textModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (roundClosed.get()) {
                    return;
                }
                firstModelSignalReceived.set(true);
                roundResponse.append(partialResponse);

                filterBuffer.append(partialResponse);
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
                                sendTokenToClient(emitter, before, clientDisconnected, generatingMarked, sessionId, clientMessageId, textModelName);
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
                                    sendTokenToClient(emitter, before, clientDisconnected, generatingMarked, sessionId, clientMessageId, textModelName);
                                }
                                filterBuffer.append(content.substring(potentialIdx));
                            } else {
                                sendTokenToClient(emitter, content, clientDisconnected, generatingMarked, sessionId, clientMessageId, textModelName);
                            }
                            break;
                        }
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
                        completeResponse,
                        generatingMarked);
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

                StreamingChatModel fallback = routingService.getOpenRouterTextFallbackModel(textModel);
                if (fallback != textModel) {
                    String fallbackName = routingService.getModelName(fallback);
                    log.warn("[SSE] forceTextOnlyResponse falling back from {} to {} for session {}",
                            textModelName, fallbackName, sessionId);
                    chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                            Phase.THINKING, fallbackName, null, null);
                    safeSend(emitter, "model", fallbackName + " (OpenRouter fallback)", null);
                    safeSend(emitter, "phase", Phase.THINKING.name(), null);
                    forceTextOnlyResponse(emitter, emitterCompleted, session, sessionId, userId, userInput,
                            history, systemPrompt, fallback, fallbackName, startTime,
                            true, clientMessageId, new StringBuilder(), clientDisconnected, generatingMarked, requiresAHP,
                            toolCallSummaries, toolNames, guardrailInstruction);
                    return;
                }

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
            ChatResponse completeResponse,
            AtomicBoolean generatingMarked) {
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

        if (generatingMarked.compareAndSet(false, true)) {
            safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
        }

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
            String startMsg = "Truy cập hệ thống: " + getFriendlyToolName(request.name()) + "...\n\n";
            safeSend(emitter, "token", Map.of("token", startMsg), MediaType.APPLICATION_JSON);

            String output;
            ToolExecutionContext.set(new ToolExecutionContext.Context(userId, sessionId, userInput));
            try {
                output = toolCallingRegistryService.execute(request);
            } finally {
                ToolExecutionContext.clear();
            }

            String endMsg = "Kết quả: " + getFriendlyToolResultSummary(request.name(), output) + "\n\n";
            safeSend(emitter, "token", Map.of("token", endMsg), MediaType.APPLICATION_JSON);

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

    private String getFriendlyToolName(String toolName) {
        if (toolName == null) return "truy vấn hệ thống";
        switch (toolName) {
            case "getMyNotifications":
            case "queryNotifications":
                return "truy xuất danh sách thông báo";
            case "getMyProjects":
            case "queryProjects":
                return "truy xuất danh sách dự án";
            case "getProjectMembers":
            case "queryProjectMembers":
                return "truy xuất danh sách thành viên dự án";
            case "getMyTasks":
            case "queryTasks":
                return "truy xuất danh sách nhiệm vụ";
            case "getTaskDetails":
                return "truy xuất chi tiết nhiệm vụ";
            case "getProjectStatus":
                return "truy xuất trạng thái dự án";
            case "getMemberWorkload":
                return "truy xuất khối lượng công việc thành viên";
            case "recommendAssignmentCandidates":
            case "recommendTaskAssignmentCandidates":
                return "phân tích và gợi ý người thực hiện nhiệm vụ";
            case "assignTaskToMember":
            case "assignTaskToMemberByName":
            case "recommendAndAssignTask":
                return "đề xuất phân công nhiệm vụ";
            default:
                return "thực thi công cụ hệ thống (" + toolName + ")";
        }
    }

    private String getFriendlyToolResultSummary(String toolName, String output) {
        if (output == null || output.isBlank()) {
            return "kết quả rỗng.";
        }
        if (output.contains("Tool execution failed") || output.contains("failed") || output.contains("Error")) {
            return "gặp lỗi hệ thống hoặc không thể thực thi.";
        }
        
        switch (toolName) {
            case "getMyNotifications":
            case "queryNotifications":
                return "tìm thấy dữ liệu thông báo liên quan.";
            case "getMyProjects":
            case "queryProjects":
                return "đã tải thông tin các dự án.";
            case "getProjectMembers":
            case "queryProjectMembers":
                return "đã lấy danh sách thành viên thành công.";
            case "getMyTasks":
            case "queryTasks":
                return "đã xác định các nhiệm vụ tương ứng.";
            case "getTaskDetails":
                return "đã lấy thông tin chi tiết nhiệm vụ.";
            case "recommendAssignmentCandidates":
            case "recommendTaskAssignmentCandidates":
                return "đã hoàn tất tính toán điểm số và xếp hạng ứng viên phù hợp.";
            default:
                return "nhận được kết quả phản hồi từ hệ thống.";
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

    private void sendTokenToClient(SseEmitter emitter, String token, AtomicBoolean clientDisconnected, AtomicBoolean generatingMarked, Long sessionId, String clientMessageId, String modelName) {
        if (generatingMarked.compareAndSet(false, true)) {
            safeSend(emitter, "token", Map.of("token", "</think>\n\n"), MediaType.APPLICATION_JSON);
            chatStreamStatusService.updatePhase(sessionId, clientMessageId, Phase.GENERATING, modelName, null, null);
            safeSend(emitter, "phase", Phase.GENERATING.name(), null);
        }
        if (!clientDisconnected.get()) {
            safeSend(emitter, "token", Map.of("token", token), MediaType.APPLICATION_JSON);
        }
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
        Map<String, Map<?, ?>> latestConfirmations = new LinkedHashMap<>();
        for (Map<String, Object> summary : toolCallSummaries) {
            Object confirmation = summary.get("confirmation");
            if (confirmation instanceof Map<?, ?> confirmationMap) {
                latestConfirmations.put(confirmationBlockKey(confirmationMap), confirmationMap);
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

        for (Map<?, ?> confirmationMap : latestConfirmations.values()) {
            try {
                blocks.add("```taskpilot-confirm\n"
                        + objectMapper.writeValueAsString(confirmationMap)
                        + "\n```");
            } catch (Exception ex) {
                log.warn("[HumanInLoop] Failed to serialize pending action metadata: {}", ex.getMessage());
            }
        }

        if (blocks.isEmpty()) {
            return responseText;
        }

        String visibleText = responseText == null ? "" : responseText.trim();
        return (visibleText + "\n\n" + String.join("\n\n", blocks)).trim();
    }

    private String confirmationBlockKey(Map<?, ?> confirmation) {
        Object toolName = confirmation.get("toolName");
        Object actionId = confirmation.get("actionId");
        Object taskId = nestedValue(confirmation, "arguments", "taskId");
        if (taskId == null) {
            taskId = nestedValue(confirmation, "preview", "taskId");
        }
        Object projectId = nestedValue(confirmation, "arguments", "projectId");
        if (projectId == null) {
            projectId = nestedValue(confirmation, "preview", "projectId");
        }
        StringBuilder key = new StringBuilder(String.valueOf(toolName == null ? "pendingAction" : toolName));
        if (taskId != null) {
            key.append("|task:").append(taskId);
        }
        if (projectId != null) {
            key.append("|project:").append(projectId);
        }
        return key.length() > 0 ? key.toString() : String.valueOf(actionId);
    }

    private Object nestedValue(Map<?, ?> source, String parentKey, String childKey) {
        Object parent = source.get(parentKey);
        if (parent instanceof Map<?, ?> nested) {
            return nested.get(childKey);
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

    private String buildSystemPrompt(Long userId) {
        UserProfileLiteDto profile = userProfilePort.findLiteById(userId).orElse(null);
        String userName = profile != null ? profile.fullName() : "Unknown User";

        PromptTemplate template = PromptTemplate.from(MASTER_PROMPT_TEMPLATE);
        return template.apply(Map.of(
                "current_date", LocalDate.now().toString(),
                "current_mode", heuristicConfigProvider.getCurrentMode(),
                "current_user_name", userName,
                "current_user_id", String.valueOf(userId)))
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

    private List<ChatMessage> cleanAndAlternateRoles(List<ChatMessage> messages, boolean isGemini) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<ChatMessage> cleaned = new ArrayList<>();
        // Always preserve the first SystemMessage if present
        int startIndex = 0;
        if (messages.get(0) instanceof SystemMessage sysMsg) {
            cleaned.add(sysMsg);
            startIndex = 1;
        }

        List<ChatMessage> body = new ArrayList<>();
        for (int i = startIndex; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage sys) {
                if (isGemini) {
                    // Gemini does not allow SystemMessage in the middle of history
                    body.add(UserMessage.from(sys.text()));
                } else {
                    body.add(msg);
                }
            } else {
                body.add(msg);
            }
        }

        if (body.isEmpty()) {
            return cleaned;
        }

        // Now, merge consecutive messages of the same type
        List<ChatMessage> alternated = new ArrayList<>();
        ChatMessage current = body.get(0);
        for (int i = 1; i < body.size(); i++) {
            ChatMessage next = body.get(i);
            if (sameRole(current, next)) {
                current = mergeMessages(current, next);
            } else {
                alternated.add(current);
                current = next;
            }
        }
        alternated.add(current);

        // For Gemini, we must ensure it alternates starting with User, then AI, then User...
        // Gemini: User -> Model -> User -> Model...
        // If there's an initial SystemMessage, it's passed as system instruction, so the remaining list must alternate.
        if (isGemini && !alternated.isEmpty() && alternated.get(0) instanceof AiMessage) {
            List<ChatMessage> temp = new ArrayList<>();
            temp.add(UserMessage.from("[System: Continued conversation]"));
            temp.addAll(alternated);
            alternated = temp;
        }

        cleaned.addAll(alternated);
        return cleaned;
    }

    private boolean sameRole(ChatMessage m1, ChatMessage m2) {
        if (m1 instanceof UserMessage && m2 instanceof UserMessage) {
            return true;
        }
        if (m1 instanceof AiMessage && m2 instanceof AiMessage) {
            return true;
        }
        if (m1 instanceof SystemMessage && m2 instanceof SystemMessage) {
            return true;
        }
        return false;
    }

    private ChatMessage mergeMessages(ChatMessage m1, ChatMessage m2) {
        String combinedText = messageText(m1) + "\n\n" + messageText(m2);
        if (m1 instanceof UserMessage) {
            return UserMessage.from(combinedText);
        }
        if (m1 instanceof AiMessage) {
            return AiMessage.from(combinedText);
        }
        return SystemMessage.from(combinedText);
    }

    private boolean hasRemainingOpenRouterKeys(StreamingChatModel model, int modelKeyAttempts) {
        return model instanceof OpenRouterMultiKeyStreamingChatModel openRouterModel
                && modelKeyAttempts < openRouterModel.keyCount();
    }

    private int openRouterKeyCount(StreamingChatModel model) {
        if (model instanceof OpenRouterMultiKeyStreamingChatModel openRouterModel) {
            return openRouterModel.keyCount();
        }
        return 1;
    }

    private record ToolLoopState(String toolName, int consecutiveCount) {
    }
}
