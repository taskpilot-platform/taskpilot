package com.taskpilot.ai.streaming.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.config.AiModelConfig;
import com.taskpilot.ai.context.ChatHistoryCompactor;
import com.taskpilot.ai.context.ChatMessageSanitizer;
import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.prompt.SystemPromptBuilder;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.ai.routing.CopilotRoutingProperties;
import com.taskpilot.ai.routing.CopilotStreamStatusCode;
import com.taskpilot.ai.service.AiLogService;
import com.taskpilot.ai.service.AiToolAccessPolicy;
import com.taskpilot.ai.service.ChatStreamStatusService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.service.SessionChatMemoryService;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.ai.service.ThinkingNarratorService;
import com.taskpilot.ai.service.ToolCallingRegistryService;
import com.taskpilot.ai.streaming.postprocess.SessionPostProcessor;
import com.taskpilot.ai.streaming.sse.AiSseTransport;
import com.taskpilot.ai.streaming.testutil.DeterministicTestScheduler;
import com.taskpilot.ai.streaming.tool.ConfirmationBlockParser;
import com.taskpilot.ai.streaming.tool.StreamingToolCoordinator;
import com.taskpilot.ai.util.TokenEstimationUtil;
import com.taskpilot.contracts.assignment.port.out.SystemSettingPort;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class StreamingChatEngineCopilotChainTest {

    @Mock private ChatSessionRepository sessionRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private AiLogService aiLogService;
    @Mock private ChatStreamStatusService chatStreamStatusService;
    @Mock private SessionChatMemoryService sessionChatMemoryService;
    @Mock private ToolCallingRegistryService toolCallingRegistryService;
    @Mock private ThinkingNarratorService thinkingNarratorService;
    @Mock private TokenCountEstimator tokenCountEstimator;
    @Mock private AiSseTransport sseTransport;
    @Mock private SystemPromptBuilder promptBuilder;
    @Mock private DirectOpenAiModelClient directModelClient;
    @Mock private SessionPostProcessor postProcessor;
    @Mock private IntermediateResponseStreamer intermediateResponseStreamer;
    @Mock private SystemSettingPort systemSettingPort;
    @Mock private AiModelConfig aiModelConfig;
    @Mock private AiToolAccessPolicy accessPolicy;

    private ObjectMapper objectMapper;
    private ChatMessageSanitizer sanitizer;
    private ChatHistoryCompactor compactor;
    private ConfirmationBlockParser confirmationParser;
    private StreamingToolCoordinator toolCoordinator;
    private TimeoutFallbackHandler timeoutFallbackHandler;
    private SmartRoutingService routingService;
    private StreamingChatEngine engine;
    private DeterministicTestScheduler scheduler;
    private AtomicReference<Instant> fakeClock;
    private CopilotRoutingProperties copilotProps;

    private final List<String> invokedModelOrder = new ArrayList<>();
    private final Map<String, ScriptedModel> scriptedModels = new HashMap<>();

    static class ScriptedModel implements StreamingChatModel {
        final String label;
        final List<String> invocationLog;
        final List<ChatRequest> receivedRequests = new ArrayList<>();
        final Deque<BiConsumer<ChatRequest, StreamingChatResponseHandler>> scripts = new ArrayDeque<>();

        ScriptedModel(String label, List<String> invocationLog) {
            this.label = label;
            this.invocationLog = invocationLog;
        }

        ScriptedModel thenText(String text) {
            scripts.add((req, h) -> {
                h.onPartialResponse(text);
                h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
            });
            return this;
        }

        ScriptedModel thenToolCalls(ToolExecutionRequest... requests) {
            scripts.add((req, h) -> h.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(List.of(requests)))
                    .build()));
            return this;
        }

        ScriptedModel thenError(Throwable error) {
            scripts.add((req, h) -> h.onError(error));
            return this;
        }

        ScriptedModel thenSilent() {
            scripts.add((req, h) -> { /* intentionally silent to trigger watchdog */ });
            return this;
        }

        ScriptedModel thenAction(BiConsumer<ChatRequest, StreamingChatResponseHandler> action) {
            scripts.add(action);
            return this;
        }

        @Override
        public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            invocationLog.add(label);
            receivedRequests.add(chatRequest);
            var script = scripts.pollFirst();
            if (script == null) {
                handler.onError(new IllegalStateException("No scripted response left for " + label));
                return;
            }
            script.accept(chatRequest, handler);
        }
    }

    private ScriptedModel modelFor(String provider, String modelName) {
        String key = provider.toUpperCase() + ":" + modelName;
        return scriptedModels.computeIfAbsent(key, k -> {
            ScriptedModel m = new ScriptedModel(k, invokedModelOrder);
            when(aiModelConfig.getModelProvider(m)).thenReturn(provider.toUpperCase());
            when(aiModelConfig.getModelName(m)).thenReturn(modelName);
            when(aiModelConfig.getOrCreateDynamicModel(eq(provider.toUpperCase()), eq(modelName), any())).thenReturn(m);
            return m;
        });
    }

    @BeforeEach
    void setUp() {
        invokedModelOrder.clear();
        scriptedModels.clear();

        objectMapper = new ObjectMapper().findAndRegisterModules();
        sanitizer = new ChatMessageSanitizer();
        compactor = mock(ChatHistoryCompactor.class);
        when(compactor.compactHistoryForRequest(anyList(), anyString())).thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));
        confirmationParser = new ConfirmationBlockParser(objectMapper);
        toolCoordinator = new StreamingToolCoordinator(toolCallingRegistryService, sseTransport, confirmationParser, objectMapper);
        scheduler = new DeterministicTestScheduler();
        fakeClock = new AtomicReference<>(Instant.parse("2026-11-23T00:00:00Z"));

        CopilotRoutingProperties props = new CopilotRoutingProperties();
        this.copilotProps = props;
        props.getTurn().setDeadlineSeconds(60);
        props.getTurn().setMaxModelAttemptsCap(0);
        props.setVerifiedModels(List.of(
                new CopilotRoutingProperties.VerifiedModel("GROQ", "openai/gpt-oss-120b", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("GEMINI", "gemini-3.5-flash", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("GEMINI", "gemini-2.5-flash", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("GEMINI", "gemini-3.1-flash-lite", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("GEMINI", "gemini-3.5-flash-lite", true, "probe"),
                new CopilotRoutingProperties.VerifiedModel("OPENROUTER", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", true, "probe")
        ));
        props.setBlockedModels(List.of(
                new CopilotRoutingProperties.BlockedModel("OPENROUTER", "nvidia/nemotron-3-super-120b-a12b:free", "uat-404"),
                new CopilotRoutingProperties.BlockedModel("OPENROUTER", "openai/gpt-oss-120b:free", "uat-404")
        ));

        when(aiModelConfig.hasConfiguredKeys(anyString())).thenReturn(Optional.of(true));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.empty());
        when(sseTransport.safeSend(any(), any(), any(), any())).thenReturn(true);
        when(promptBuilder.buildCompactSystemPrompt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.getPeriodicSteps(anyString())).thenReturn(List.of());
        when(messageRepository.findLastNBySessionId(anyLong(), any())).thenReturn(List.of());
        when(tokenCountEstimator.estimateTokenCountInMessages(any())).thenReturn(200);
        when(tokenCountEstimator.estimateTokenCountInText(anyString())).thenReturn(20);
        when(toolCallingRegistryService.selectToolNames(anyString(), anyInt(), anyBoolean()))
                .thenReturn(List.of("queryTasks", "recommendAndAssignTask"));
        when(toolCallingRegistryService.toolSpecificationsByNames(anyList()))
                .thenReturn(List.of(
                        ToolSpecification.builder().name("queryTasks").description("Query tasks").build(),
                        ToolSpecification.builder().name("recommendAndAssignTask").description("Recommend and prepare assignment").build()
                ));

        TokenEstimationUtil tokenEstimationUtil = new TokenEstimationUtil();
        routingService = new SmartRoutingService(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                tokenEstimationUtil, null, systemSettingPort, aiModelConfig, props
        );
        ReflectionTestUtils.setField(routingService, "groqEnabled", true);
        ReflectionTestUtils.setField(routingService, "groqReasoningModelName", "openai/gpt-oss-120b");
        ReflectionTestUtils.setField(routingService, "useGeminiForTools", true);
        ReflectionTestUtils.setField(routingService, "geminiModelName", "gemini-3.5-flash");
        ReflectionTestUtils.setField(routingService, "geminiFallback1ModelName", "gemini-2.5-flash");
        ReflectionTestUtils.setField(routingService, "geminiFallback2ModelName", "gemini-3.1-flash-lite");
        ReflectionTestUtils.setField(routingService, "geminiFallback3ModelName", "gemini-2.5-flash-lite");
        ReflectionTestUtils.setField(routingService, "geminiFallback4ModelName", "gemini-2.5-pro");
        ReflectionTestUtils.setField(routingService, "geminiFallback5ModelName", "gemini-3.5-flash-lite");
        ReflectionTestUtils.setField(routingService, "openRouterEnabled", true);
        ReflectionTestUtils.setField(routingService, "openRouterReasoningModelName", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free");
        ReflectionTestUtils.setField(routingService, "tokenThreshold", 15000);
        ReflectionTestUtils.setField(routingService, "toolKeywordsRaw", "project,task,giao viec,cong viec,du an,xac nhan");
        ReflectionTestUtils.setField(routingService, "ahpFallbackKeywordsRaw", "phan cong,giao viec,chon ai");

        timeoutFallbackHandler = new TimeoutFallbackHandler(
                sseTransport, chatStreamStatusService, routingService, sanitizer, compactor, confirmationParser, postProcessor, scheduler);
        engine = new StreamingChatEngine(
                sessionRepository, messageRepository, routingService, aiLogService, chatStreamStatusService,
                sessionChatMemoryService, toolCallingRegistryService, thinkingNarratorService, tokenCountEstimator,
                objectMapper, sseTransport, sanitizer, compactor, promptBuilder, toolCoordinator,
                confirmationParser, directModelClient, postProcessor, timeoutFallbackHandler, intermediateResponseStreamer
        );
        ReflectionTestUtils.setField(engine, "streamFirstResponseTimeoutSeconds", 20);
        ReflectionTestUtils.setField(engine, "maxOutputTokens", 3500);
        engine.setClockForTesting(fakeClock::get);
        engine.setTimeoutSchedulerForTesting(scheduler);
    }

    @Test
    @DisplayName("Main acceptance: Groq runs tools and creates proposal, then fails 429 -> Gemini takes over text-only, single proposal, task assigned once after confirm")
    void groqFailsAfterToolRun_geminiTakesOver_singleProposal_assignOnlyAfterConfirm() throws Exception {
        String uatPrompt = "Trong dự án ID 3, hãy đề xuất người phù hợp nhất cho công việc ID 6 theo kỹ năng đã lưu, rồi chuẩn bị giao việc để tôi xác nhận.";
        PendingAiActionService pendingService = new PendingAiActionService(accessPolicy);
        when(accessPolicy.fingerprint(anyString(), any(), anyLong(), anyLong(), anyBoolean())).thenReturn("rev-1");

        AtomicReference<Long> taskAssigneeId = new AtomicReference<>(null);
        AtomicInteger assignExecutions = new AtomicInteger(0);
        AtomicReference<ConfirmationRequiredDto> createdConfirmation = new AtomicReference<>();

        ToolExecutionRequest queryReq = ToolExecutionRequest.builder().id("t1").name("queryTasks").arguments("{\"projectId\":3}").build();
        ToolExecutionRequest assignReq = ToolExecutionRequest.builder().id("t2").name("recommendAndAssignTask").arguments("{\"projectId\":3,\"taskId\":6}").build();

        when(toolCallingRegistryService.execute(argThat(r -> r != null && "queryTasks".equals(r.name()))))
                .thenReturn("[{\"id\":6,\"title\":\"Copilot assignment UAT\"}]");
        when(toolCallingRegistryService.execute(argThat(r -> r != null && "recommendAndAssignTask".equals(r.name()))))
                .thenAnswer(inv -> {
                    ConfirmationRequiredDto dto = pendingService.create(
                            7L, 101L, "recommendAndAssignTask", "Giao task 6 cho An Chat UAT",
                            Map.of("projectId", 3, "taskId", 6, "assigneeId", 22),
                            Map.of("taskId", 6, "projectId", 3, "candidate", "An Chat UAT"),
                            () -> {
                                assignExecutions.incrementAndGet();
                                taskAssigneeId.set(22L);
                                return Map.of("taskId", 6, "assigneeId", 22);
                            });
                    createdConfirmation.set(dto);
                    return objectMapper.writeValueAsString(dto);
                });

        ScriptedModel groq = modelFor("GROQ", "openai/gpt-oss-120b")
                .thenToolCalls(queryReq)
                .thenToolCalls(assignReq)
                .thenError(new RuntimeException("com.openai.errors.RateLimitException: 429: null"));
        ScriptedModel geminiA = modelFor("GEMINI", "gemini-3.5-flash")
                .thenText("Đề xuất giao task 6 cho An Chat UAT. Vui lòng bấm Xác nhận bên dưới.");

        SmartRoutingService.RoutingDecision decision = routingService.route(uatPrompt);
        assertThat(decision.model()).isSameAs(groq);
        assertThat(decision.requiresTools()).isTrue();

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(101L);
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean completed = new AtomicBoolean(false);
        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("System"), UserMessage.from(uatPrompt)));

        engine.doStream(emitter, completed, session, 101L, 7L, uatPrompt, history, "System",
                decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-main",
                decision.requiresAHP(), decision.requiresTools(), 0);

        // Exact invoked model order: Groq (round 0, 1, 2) -> Gemini A (text-only finalizer)
        assertThat(invokedModelOrder).containsExactly(
                "GROQ:openai/gpt-oss-120b",
                "GROQ:openai/gpt-oss-120b",
                "GROQ:openai/gpt-oss-120b",
                "GEMINI:gemini-3.5-flash"
        );

        // Tools executed strictly once each
        verify(toolCallingRegistryService, times(1)).execute(argThat(r -> r != null && "queryTasks".equals(r.name())));
        verify(toolCallingRegistryService, times(1)).execute(argThat(r -> r != null && "recommendAndAssignTask".equals(r.name())));

        // Gemini A took over in text-only mode (no tools!) and still received current UAT prompt containing "xác nhận"
        assertThat(geminiA.receivedRequests).hasSize(1);
        assertThat(geminiA.receivedRequests.get(0).toolSpecifications()).isNullOrEmpty();
        String geminiRequestText = geminiA.receivedRequests.get(0).messages().toString();
        assertThat(geminiRequestText).contains("chuẩn bị giao việc để tôi xác nhận");

        // Task NOT assigned before explicit confirmation
        assertThat(taskAssigneeId.get()).isNull();
        assertThat(assignExecutions.get()).isZero();

        // Final SSE done contains exactly one taskpilot-confirm block
        ArgumentCaptor<Object> doneCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("done"), doneCaptor.capture(), any());
        String donePayload = String.valueOf(doneCaptor.getValue());
        assertThat(donePayload.split("```taskpilot-confirm", -1)).hasSize(2);
        assertThat(donePayload).contains(createdConfirmation.get().actionId());

        // Explicit confirmation executes assignment strictly once; second claim with different key fails 409
        CurrentActor actor = new CurrentActor(7L, "manager@example.com", "USER", 1L);
        pendingService.confirmExplicit(createdConfirmation.get().actionId(), actor, 101L,
                "idem-1", createdConfirmation.get().payloadHash());
        assertThat(taskAssigneeId.get()).isEqualTo(22L);
        assertThat(assignExecutions.get()).isEqualTo(1);

        assertThatThrownBy(() -> pendingService.confirmExplicit(
                createdConfirmation.get().actionId(), actor, 101L, "idem-2", createdConfirmation.get().payloadHash()))
                .isInstanceOf(BusinessException.class);
        assertThat(assignExecutions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Groq-first across LIGHT, TOOL-FRIENDLY, REASONING when DB absent or empty")
    void groqFirstAcrossAllBranchesWhenDbAbsentOrEmpty() {
        ScriptedModel groq = modelFor("GROQ", "openai/gpt-oss-120b");

        assertThat(routingService.route("Xin chào, bạn giúp được gì?").model()).isSameAs(groq);
        assertThat(routingService.route("Liệt kê task dự án 3").model()).isSameAs(groq);
        assertThat(routingService.route("Chọn ai phù hợp để phân công task 12?").model()).isSameAs(groq);

        when(systemSettingPort.findJsonObjectByKey("ai.model_priority"))
                .thenReturn(Optional.of(Map.of("models", List.of())));
        assertThat(routingService.route("Xin chào").model()).isSameAs(groq);
    }

    @Test
    @DisplayName("Groq fails -> Gemini A 429 -> Gemini B succeeds (assert exact invoked order)")
    void groqFail_geminiA429_geminiBSucceeds() {
        modelFor("GROQ", "openai/gpt-oss-120b").thenError(new RuntimeException("503 Service Unavailable"));
        modelFor("GEMINI", "gemini-3.5-flash").thenError(new RuntimeException("429: Rate limit exceeded; retryDelay: 35s"));
        modelFor("GEMINI", "gemini-2.5-flash").thenText("Xin chào từ Gemini 2.5 Flash!");
        ScriptedModel openRouter = modelFor("OPENROUTER", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free");

        SmartRoutingService.RoutingDecision decision = routingService.route("Xin chào");
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(202L);
        SseEmitter emitter = mock(SseEmitter.class);

        engine.doStream(emitter, new AtomicBoolean(false), session, 202L, 7L, "Xin chào",
                new ArrayList<>(List.of(SystemMessage.from("Sys"), UserMessage.from("Xin chào"))),
                "Sys", decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-2",
                false, false, 0);

        assertThat(invokedModelOrder).containsExactly(
                "GROQ:openai/gpt-oss-120b",
                "GEMINI:gemini-3.5-flash",
                "GEMINI:gemini-2.5-flash"
        );
        assertThat(openRouter.receivedRequests).isEmpty();
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("phase"), eq(Phase.FINALIZED.name()), any());
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("done"), eq("Xin chào từ Gemini 2.5 Flash!"), any());
    }

    @Test
    @DisplayName("All Gemini fail -> OpenRouter succeeds (assert exact invoked order)")
    void allGeminiFail_openRouterSucceeds() {
        modelFor("GROQ", "openai/gpt-oss-120b").thenError(new RuntimeException("503"));
        modelFor("GEMINI", "gemini-3.5-flash").thenError(new RuntimeException("429"));
        modelFor("GEMINI", "gemini-2.5-flash").thenError(new RuntimeException("429"));
        modelFor("GEMINI", "gemini-3.1-flash-lite").thenError(new RuntimeException("429"));
        modelFor("GEMINI", "gemini-3.5-flash-lite").thenError(new RuntimeException("429"));
        modelFor("OPENROUTER", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free").thenText("OK from OpenRouter");

        SmartRoutingService.RoutingDecision decision = routingService.route("Xin chào");
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(203L);
        SseEmitter emitter = mock(SseEmitter.class);

        engine.doStream(emitter, new AtomicBoolean(false), session, 203L, 7L, "Xin chào",
                new ArrayList<>(List.of(SystemMessage.from("Sys"), UserMessage.from("Xin chào"))),
                "Sys", decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-3",
                false, false, 0);

        assertThat(invokedModelOrder).containsExactly(
                "GROQ:openai/gpt-oss-120b",
                "GEMINI:gemini-3.5-flash",
                "GEMINI:gemini-2.5-flash",
                "GEMINI:gemini-3.1-flash-lite",
                "GEMINI:gemini-3.5-flash-lite",
                "OPENROUTER:nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"
        );
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("done"), eq("OK from OpenRouter"), any());
    }

    @Test
    @DisplayName("All candidates exhausted emits FAILED + COPILOT_MODELS_EXHAUSTED status code with no duplicate model calls")
    void allCandidatesExhausted_emitsStatusCodeAndNoDuplicates() {
        modelFor("GROQ", "openai/gpt-oss-120b").thenError(new RuntimeException("429"));
        modelFor("GEMINI", "gemini-3.5-flash").thenError(new RuntimeException("429"));
        modelFor("GEMINI", "gemini-2.5-flash").thenError(new RuntimeException("503"));
        modelFor("GEMINI", "gemini-3.1-flash-lite").thenError(new RuntimeException("429"));
        modelFor("GEMINI", "gemini-3.5-flash-lite").thenError(new RuntimeException("429"));
        modelFor("OPENROUTER", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free").thenError(new RuntimeException("429"));

        SmartRoutingService.RoutingDecision decision = routingService.route("Xin chào");
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(204L);
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean completed = new AtomicBoolean(false);

        engine.doStream(emitter, completed, session, 204L, 7L, "Xin chào",
                new ArrayList<>(List.of(SystemMessage.from("Sys"), UserMessage.from("Xin chào"))),
                "Sys", decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-4",
                false, false, 0);

        assertThat(invokedModelOrder).containsExactly(
                "GROQ:openai/gpt-oss-120b",
                "GEMINI:gemini-3.5-flash",
                "GEMINI:gemini-2.5-flash",
                "GEMINI:gemini-3.1-flash-lite",
                "GEMINI:gemini-3.5-flash-lite",
                "OPENROUTER:nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"
        );
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("phase"), eq(Phase.FAILED.name()), any());
        ArgumentCaptor<Object> errCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("error"), errCaptor.capture(), any());
        assertThat(String.valueOf(errCaptor.getValue()))
                .contains(CopilotStreamStatusCode.COPILOT_MODELS_EXHAUSTED.code())
                .contains("\"attempted\":6");
        verify(sseTransport, times(1)).safeComplete(eq(emitter), eq(completed));
    }

    @Test
    @DisplayName("Shared 60s turn deadline stops failover with COPILOT_TURN_DEADLINE_EXCEEDED")
    void sharedDeadlineExceeded_emitsDeadlineStatusCode() {
        modelFor("GROQ", "openai/gpt-oss-120b").thenAction((req, h) -> {
            fakeClock.set(fakeClock.get().plusSeconds(25));
            h.onError(new RuntimeException("503"));
        });
        modelFor("GEMINI", "gemini-3.5-flash").thenAction((req, h) -> {
            fakeClock.set(fakeClock.get().plusSeconds(25));
            h.onError(new RuntimeException("503"));
        });
        modelFor("GEMINI", "gemini-2.5-flash").thenAction((req, h) -> {
            fakeClock.set(fakeClock.get().plusSeconds(11)); // elapsed = 61s > 60s
            h.onError(new RuntimeException("503"));
        });
        ScriptedModel uncalled = modelFor("GEMINI", "gemini-3.1-flash-lite");

        SmartRoutingService.RoutingDecision decision = routingService.route("Xin chào");
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(205L);
        SseEmitter emitter = mock(SseEmitter.class);

        engine.doStream(emitter, new AtomicBoolean(false), session, 205L, 7L, "Xin chào",
                new ArrayList<>(List.of(SystemMessage.from("Sys"), UserMessage.from("Xin chào"))),
                "Sys", decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-5",
                false, false, 0);

        assertThat(invokedModelOrder).containsExactly(
                "GROQ:openai/gpt-oss-120b",
                "GEMINI:gemini-3.5-flash",
                "GEMINI:gemini-2.5-flash"
        );
        assertThat(uncalled.receivedRequests).isEmpty();
        ArgumentCaptor<Object> errCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("error"), errCaptor.capture(), any());
        assertThat(String.valueOf(errCaptor.getValue()))
                .contains(CopilotStreamStatusCode.COPILOT_TURN_DEADLINE_EXCEEDED.code())
                .contains("\"deadlineSeconds\":60");
    }

    @Test
    @DisplayName("Tool ledger prevents re-executing already-run tool when model switches after tool round")
    void modelSwitchAfterReadTool_reusesCachedToolOutputWithoutReExecuting() {
        ToolExecutionRequest queryReq = ToolExecutionRequest.builder().id("q1").name("queryTasks").arguments("{\"projectId\":3}").build();
        when(toolCallingRegistryService.execute(argThat(r -> r != null && "queryTasks".equals(r.name()))))
                .thenReturn("[{\"id\":6,\"title\":\"Task 6\"}]");

        modelFor("GROQ", "openai/gpt-oss-120b")
                .thenToolCalls(queryReq)
                .thenError(new RuntimeException("503"));
        modelFor("GEMINI", "gemini-3.5-flash")
                .thenToolCalls(queryReq) // tries to repeat exact same queryTasks!
                .thenText("Danh sách có Task 6.");

        SmartRoutingService.RoutingDecision decision = routingService.route("Liệt kê task dự án 3");
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(206L);
        SseEmitter emitter = mock(SseEmitter.class);

        engine.doStream(emitter, new AtomicBoolean(false), session, 206L, 7L, "Liệt kê task dự án 3",
                new ArrayList<>(List.of(SystemMessage.from("Sys"), UserMessage.from("Liệt kê task dự án 3"))),
                "Sys", decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-6",
                false, true, 0);

        verify(toolCallingRegistryService, times(1)).execute(any());
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("done"), eq("Danh sách có Task 6."), any());
        verify(sseTransport, never()).safeSend(eq(emitter), eq("token"),
                argThat(payload -> String.valueOf(payload).contains("Dùng lại kết quả")), any());
    }

    @Test
    @DisplayName("Hard 60s turn deadline terminates turn even when model emits 1 partial token and then hangs")
    void partialTokenThenHang_enforcesHardTurnDeadlineAndIgnoresLateCallbacks() {
        AtomicReference<StreamingChatResponseHandler> capturedHandler = new AtomicReference<>();
        modelFor("GROQ", "openai/gpt-oss-120b").thenAction((req, h) -> {
            capturedHandler.set(h);
            h.onPartialResponse("Xin chào...");
            // Intentionally hang without calling onCompleteResponse or onError
        });

        SmartRoutingService.RoutingDecision decision = routingService.route("Xin chào");
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(208L);
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean completed = new AtomicBoolean(false);

        engine.doStream(emitter, completed, session, 208L, 7L, "Xin chào",
                new ArrayList<>(List.of(SystemMessage.from("Sys"), UserMessage.from("Xin chào"))),
                "Sys", decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-8",
                false, false, 0);

        // Passing first-response timeout (20s) must NOT fire timeoutFuture because first token arrived
        scheduler.advanceTime(25, TimeUnit.SECONDS);
        verify(sseTransport, never()).safeSend(eq(emitter), eq("error"), any(), any());
        verify(sseTransport, never()).safeComplete(eq(emitter), eq(completed));

        // Advancing to 60s hard turn deadline must terminate the hung turn
        scheduler.advanceTime(35, TimeUnit.SECONDS);

        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("reset_stream"), any(), any());
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("phase"), eq(Phase.FAILED.name()), any());
        ArgumentCaptor<Object> errCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("error"), errCaptor.capture(), any());
        assertThat(String.valueOf(errCaptor.getValue()))
                .contains(CopilotStreamStatusCode.COPILOT_TURN_DEADLINE_EXCEEDED.code())
                .contains("\"deadlineSeconds\":60");
        verify(sseTransport, times(1)).safeComplete(eq(emitter), eq(completed));

        // Late callbacks from the hung model must be ignored
        capturedHandler.get().onPartialResponse(" late token after deadline");
        capturedHandler.get().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("late")).build());
        verify(sseTransport, never()).safeSend(eq(emitter), eq("done"), any(), any());
    }

    @Test
    @DisplayName("Mid-stream error after partial text emits reset_stream before switching so next model answer is not concatenated")
    void partialVisibleTextThenModelError_emitsResetStreamBeforeNextModelTakesOver() {
        modelFor("GROQ", "openai/gpt-oss-120b").thenAction((req, h) -> {
            h.onPartialResponse("Nháp dở từ Groq...");
            h.onError(new RuntimeException("503 stream dropped mid-response"));
        });
        modelFor("GEMINI", "gemini-3.5-flash").thenText("Câu trả lời hoàn chỉnh từ Gemini.");

        SmartRoutingService.RoutingDecision decision = routingService.route("Xin chào");
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(209L);
        SseEmitter emitter = mock(SseEmitter.class);

        engine.doStream(emitter, new AtomicBoolean(false), session, 209L, 7L, "Xin chào",
                new ArrayList<>(List.of(SystemMessage.from("Sys"), UserMessage.from("Xin chào"))),
                "Sys", decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-9",
                false, false, 0);

        assertThat(invokedModelOrder).containsExactly(
                "GROQ:openai/gpt-oss-120b",
                "GEMINI:gemini-3.5-flash"
        );
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("reset_stream"), any(), any());
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("model"), eq("gemini-3.5-flash"), any());
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("done"), eq("Câu trả lời hoàn chỉnh từ Gemini."), any());
    }

    @Test
    @DisplayName("Model returns tool call before deadline, slow tool exceeds 60s turn deadline -> terminates SSE safely without replaying write")
    void modelReturnsToolCallBeforeDeadline_slowToolExceedsTurnDeadline_terminatesSseSafelyWithoutReplayingWrite() {
        ToolExecutionRequest assignReq = ToolExecutionRequest.builder()
                .id("slow-write-1")
                .name("recommendAndAssignTask")
                .arguments("{\"projectId\":3,\"taskId\":6}")
                .build();

        AtomicInteger toolExecutions = new AtomicInteger(0);
        AtomicBoolean toolInterrupted = new AtomicBoolean(false);
        java.util.concurrent.CountDownLatch blockForever = new java.util.concurrent.CountDownLatch(1);

        when(toolCallingRegistryService.execute(argThat(r -> r != null && "recommendAndAssignTask".equals(r.name()))))
                .thenAnswer(inv -> {
                    toolExecutions.incrementAndGet();
                    try {
                        blockForever.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        toolInterrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted on turn deadline", ie);
                    }
                    return "{\"unexpected\":\"should not complete\"}";
                });

        // Model returns tool_call at t=59.8s (before 60s turn deadline; 200ms left for tool execution)
        modelFor("GROQ", "openai/gpt-oss-120b").thenAction((req, h) -> {
            fakeClock.set(fakeClock.get().plusMillis(59_800));
            h.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(List.of(assignReq)))
                    .build());
        });
        ScriptedModel uncalledGemini = modelFor("GEMINI", "gemini-3.5-flash");

        SmartRoutingService.RoutingDecision decision = routingService.route("Giao task 6 trong dự án 3 để tôi xác nhận");
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(210L);
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean completed = new AtomicBoolean(false);

        long wallStartNs = System.nanoTime();
        engine.doStream(emitter, completed, session, 210L, 7L,
                "Giao task 6 trong dự án 3 để tôi xác nhận",
                new ArrayList<>(List.of(SystemMessage.from("Sys"), UserMessage.from("Giao task 6 trong dự án 3 để tôi xác nhận"))),
                "Sys", decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-10",
                false, true, 0);
        long wallElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wallStartNs);

        // Must stop right at the remaining ~200ms budget (not wait 10s for hung tool)
        assertThat(wallElapsedMs).isLessThan(3_000L);
        assertThat(toolExecutions.get()).isEqualTo(1);
        assertThat(toolInterrupted.get()).isTrue();
        assertThat(invokedModelOrder).containsExactly("GROQ:openai/gpt-oss-120b");
        assertThat(uncalledGemini.receivedRequests).isEmpty();

        verify(toolCallingRegistryService, times(1)).execute(any());
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("phase"), eq(Phase.FAILED.name()), any());
        ArgumentCaptor<Object> errCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("error"), errCaptor.capture(), any());
        assertThat(String.valueOf(errCaptor.getValue()))
                .contains(CopilotStreamStatusCode.COPILOT_TURN_DEADLINE_EXCEEDED.code())
                .contains("\"deadlineSeconds\":60");
        verify(sseTransport, never()).safeSend(eq(emitter), eq("done"), any(), any());
        verify(sseTransport, times(1)).safeComplete(eq(emitter), eq(completed));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("Tool ignoring interrupt cannot delay SSE past turn deadline -> independent timer closes SSE, preserves existing confirmation card, and ignores late tool completion without replaying write")
    void uninterruptibleToolExceedsTurnDeadline_timerClosesSseIndependently_preservesConfirmation_andIgnoresLateToolCompletion(boolean sameRound) throws Exception {
        java.util.concurrent.ScheduledExecutorService realScheduler = java.util.concurrent.Executors.newScheduledThreadPool(2);
        try {
            // Configure a 3-second hard turn deadline (> 2s MIN_CALL_WINDOW_MS so round 1 can start)
            // with real wall-clock scheduler & clock
            copilotProps.getTurn().setDeadlineSeconds(3);
            engine.setClockForTesting(java.time.InstantSource.system());
            engine.setTimeoutSchedulerForTesting(realScheduler);

            PendingAiActionService pendingService = new PendingAiActionService(accessPolicy);
            when(accessPolicy.fingerprint(anyString(), any(), anyLong(), anyLong(), anyBoolean())).thenReturn("rev-unint");

            AtomicInteger assignExecutions = new AtomicInteger(0);
            AtomicReference<ConfirmationRequiredDto> createdConfirmation = new AtomicReference<>();
            AtomicBoolean slowToolFinished = new AtomicBoolean(false);
            AtomicInteger slowToolInterruptsObserved = new AtomicInteger(0);
            java.util.concurrent.CountDownLatch slowToolInterrupted = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch slowToolStarted = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseSlowTool = new java.util.concurrent.CountDownLatch(1);

            AtomicInteger safeSendCount = new AtomicInteger(0);
            AtomicInteger safeCompleteCount = new AtomicInteger(0);
            java.util.concurrent.CountDownLatch sseClosedLatch = new java.util.concurrent.CountDownLatch(1);

            when(sseTransport.safeSend(any(), any(), any(), any())).thenAnswer(inv -> {
                safeSendCount.incrementAndGet();
                return true;
            });
            doAnswer(inv -> {
                safeCompleteCount.incrementAndGet();
                AtomicBoolean flag = inv.getArgument(1);
                if (flag != null) {
                    flag.set(true);
                }
                sseClosedLatch.countDown();
                return null;
            }).when(sseTransport).safeComplete(any(), any());

            ToolExecutionRequest assignReq = ToolExecutionRequest.builder()
                    .id("u-assign-1")
                    .name("recommendAndAssignTask")
                    .arguments("{\"projectId\":3,\"taskId\":6}")
                    .build();
            ToolExecutionRequest slowQueryReq = ToolExecutionRequest.builder()
                    .id("u-slow-2")
                    .name("queryTasks")
                    .arguments("{\"projectId\":3}")
                    .build();

            when(toolCallingRegistryService.execute(argThat(r -> r != null && "recommendAndAssignTask".equals(r.name()))))
                    .thenAnswer(inv -> {
                        ConfirmationRequiredDto dto = pendingService.create(
                                7L, 211L, "recommendAndAssignTask", "Giao task 6 cho An Chat UAT",
                                Map.of("projectId", 3, "taskId", 6, "assigneeId", 22),
                                Map.of("taskId", 6, "projectId", 3, "candidate", "An Chat UAT"),
                                () -> {
                                    assignExecutions.incrementAndGet();
                                    return Map.of("taskId", 6, "assigneeId", 22);
                                });
                        createdConfirmation.set(dto);
                        return objectMapper.writeValueAsString(dto);
                    });

            when(toolCallingRegistryService.execute(argThat(r -> r != null && "queryTasks".equals(r.name()))))
                    .thenAnswer(inv -> {
                        slowToolStarted.countDown();
                        // Deliberately ignore Thread.interrupt() until test releases releaseSlowTool AFTER SSE is closed
                        while (true) {
                            try {
                                if (releaseSlowTool.await(5, TimeUnit.SECONDS)) {
                                    break;
                                }
                            } catch (InterruptedException ignored) {
                                slowToolInterruptsObserved.incrementAndGet();
                                slowToolInterrupted.countDown();
                            }
                        }
                        slowToolFinished.set(true);
                        return "[{\"id\":6,\"title\":\"Late result after SSE closed\"}]";
                    });

            ScriptedModel groq = modelFor("GROQ", "openai/gpt-oss-120b");
            if (sameRound) {
                groq.thenToolCalls(assignReq, slowQueryReq);
            } else {
                groq.thenToolCalls(assignReq).thenToolCalls(slowQueryReq);
            }
            ScriptedModel uncalledGemini = modelFor("GEMINI", "gemini-3.5-flash");

            SmartRoutingService.RoutingDecision decision = routingService.route("Giao task 6 trong dự án 3 để tôi xác nhận");
            ChatSessionEntity session = new ChatSessionEntity();
            session.setId(211L);
            SseEmitter emitter = mock(SseEmitter.class);
            AtomicBoolean completed = new AtomicBoolean(false);

            java.util.concurrent.CompletableFuture<Void> streamFuture = java.util.concurrent.CompletableFuture.runAsync(() ->
                    engine.doStream(emitter, completed, session, 211L, 7L,
                            "Giao task 6 trong dự án 3 để tôi xác nhận",
                            new ArrayList<>(List.of(SystemMessage.from("Sys"), UserMessage.from("Giao task 6 trong dự án 3 để tôi xác nhận"))),
                            "Sys", decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-11",
                            false, true, 0));

            try {
            // 1. Confirm slow tool is actively running inside StructuredTaskScope
            assertThat(slowToolStarted.await(3, TimeUnit.SECONDS)).isTrue();

            // 2. Turn timer MUST close SSE at the ~3s deadline WITHOUT waiting for the uninterruptible tool
            assertThat(sseClosedLatch.await(4500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(slowToolFinished.get()).isFalse();
            assertThat(completed.get()).isTrue();
            assertThat(createdConfirmation.get()).isNotNull();
            assertThat(assignExecutions.get()).isZero();

            ArgumentCaptor<Object> doneCaptor = ArgumentCaptor.forClass(Object.class);
            verify(sseTransport, times(1)).safeSend(eq(emitter), eq("phase"), eq(Phase.FINALIZED.name()), any());
            verify(sseTransport, times(1)).safeSend(eq(emitter), eq("done"), doneCaptor.capture(), any());
            assertThat(String.valueOf(doneCaptor.getValue()))
                    .contains(CopilotStreamStatusCode.COPILOT_CONFIRMATION_EXPLANATION_INCOMPLETE.code())
                    .contains("```taskpilot-confirm")
                    .contains(createdConfirmation.get().actionId());

            int sendsAtClose = safeSendCount.get();
            int completesAtClose = safeCompleteCount.get();

            // The scope timeout and SSE timer run independently. Await the actual cancellation
            // before releasing the tool, so a faster SSE timer cannot hide the interrupt.
            assertThat(slowToolInterrupted.await(3, TimeUnit.SECONDS)).isTrue();

            // 3. Now release the uninterruptible tool so StructuredTaskScope.close() finishes late
            releaseSlowTool.countDown();
            streamFuture.get(3, TimeUnit.SECONDS);

            // 4. Late tool cleanup & completion must NOT emit any extra SSE events, call another model, or replay writes
            assertThat(slowToolFinished.get()).isTrue();
            assertThat(slowToolInterruptsObserved.get()).isGreaterThanOrEqualTo(1);
            assertThat(safeSendCount.get()).isEqualTo(sendsAtClose);
            assertThat(safeCompleteCount.get()).isEqualTo(completesAtClose).isEqualTo(1);
            assertThat(invokedModelOrder).hasSize(sameRound ? 1 : 2)
                    .containsOnly("GROQ:openai/gpt-oss-120b");
            verify(sseTransport, times(1)).safeSend(eq(emitter), eq("tool"),
                    argThat(payload -> String.valueOf(payload).contains(createdConfirmation.get().actionId())), any());
            assertThat(uncalledGemini.receivedRequests).isEmpty();
            verify(toolCallingRegistryService, times(1)).execute(argThat(r -> r != null && "recommendAndAssignTask".equals(r.name())));
            verify(toolCallingRegistryService, times(1)).execute(argThat(r -> r != null && "queryTasks".equals(r.name())));
            assertThat(assignExecutions.get()).isZero();
            } finally {
                releaseSlowTool.countDown();
                streamFuture.get(3, TimeUnit.SECONDS);
            }
        } finally {
            realScheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("Proposal created then all fallback models fail -> FINALIZED with status code COPILOT_CONFIRMATION_EXPLANATION_INCOMPLETE and confirmation card preserved")
    void proposalCreatedThenAllModelsFail_emitsConfirmationStatusCodeInsteadOfFailed() throws Exception {
        PendingAiActionService pendingService = new PendingAiActionService(accessPolicy);
        when(accessPolicy.fingerprint(anyString(), any(), anyLong(), anyLong(), anyBoolean())).thenReturn("rev-2");

        ToolExecutionRequest assignReq = ToolExecutionRequest.builder().id("a1").name("recommendAndAssignTask").arguments("{\"projectId\":3,\"taskId\":6}").build();
        when(toolCallingRegistryService.execute(argThat(r -> r != null && "recommendAndAssignTask".equals(r.name()))))
                .thenAnswer(inv -> {
                    ConfirmationRequiredDto dto = pendingService.create(
                            7L, 207L, "recommendAndAssignTask", "Giao task 6",
                            Map.of("projectId", 3, "taskId", 6), Map.of("taskId", 6), () -> "assigned");
                    return objectMapper.writeValueAsString(dto);
                });

        modelFor("GROQ", "openai/gpt-oss-120b")
                .thenToolCalls(assignReq)
                .thenError(new RuntimeException("429"));
        // All remaining models fail
        modelFor("GEMINI", "gemini-3.5-flash").thenError(new RuntimeException("429"));
        modelFor("GEMINI", "gemini-2.5-flash").thenError(new RuntimeException("429"));
        modelFor("GEMINI", "gemini-3.1-flash-lite").thenError(new RuntimeException("429"));
        modelFor("GEMINI", "gemini-3.5-flash-lite").thenError(new RuntimeException("429"));
        modelFor("OPENROUTER", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free").thenError(new RuntimeException("429"));

        SmartRoutingService.RoutingDecision decision = routingService.route("Giao task 6 trong dự án 3 để tôi xác nhận");
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(207L);
        SseEmitter emitter = mock(SseEmitter.class);

        engine.doStream(emitter, new AtomicBoolean(false), session, 207L, 7L,
                "Giao task 6 trong dự án 3 để tôi xác nhận",
                new ArrayList<>(List.of(SystemMessage.from("Sys"), UserMessage.from("Giao task 6 trong dự án 3 để tôi xác nhận"))),
                "Sys", decision.model(), decision.modelName(), System.currentTimeMillis(), false, "msg-7",
                false, true, 0);

        ArgumentCaptor<Object> doneCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("phase"), eq(Phase.FINALIZED.name()), any());
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("done"), doneCaptor.capture(), any());
        String donePayload = String.valueOf(doneCaptor.getValue());
        assertThat(donePayload)
                .contains(CopilotStreamStatusCode.COPILOT_CONFIRMATION_EXPLANATION_INCOMPLETE.code())
                .contains("```taskpilot-confirm");
    }
}
