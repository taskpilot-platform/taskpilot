package com.taskpilot.ai.streaming.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.context.ChatHistoryCompactor;
import com.taskpilot.ai.context.ChatMessageSanitizer;
import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.prompt.SystemPromptBuilder;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.ai.service.AiLogService;
import com.taskpilot.ai.service.ChatStreamStatusService;
import com.taskpilot.ai.service.SessionChatMemoryService;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.ai.service.ThinkingNarratorService;
import com.taskpilot.ai.service.ToolCallingRegistryService;
import com.taskpilot.ai.streaming.postprocess.SessionPostProcessor;
import com.taskpilot.ai.streaming.sse.AiSseTransport;
import com.taskpilot.ai.streaming.tool.ConfirmationBlockParser;
import com.taskpilot.ai.streaming.tool.StreamingToolCoordinator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.taskpilot.contracts.assignment.port.out.SystemSettingPort;
import com.taskpilot.ai.config.AiModelConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class StreamingChatEngineFallbackInvocationTest {

    @Mock private ChatSessionRepository sessionRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private SmartRoutingService routingService;
    @Mock private AiLogService aiLogService;
    @Mock private ChatStreamStatusService chatStreamStatusService;
    @Mock private SessionChatMemoryService sessionChatMemoryService;
    @Mock private ToolCallingRegistryService toolCallingRegistryService;
    @Mock private ThinkingNarratorService thinkingNarratorService;
    @Mock private TokenCountEstimator tokenCountEstimator;
    @Mock private ObjectMapper objectMapper;
    @Mock private AiSseTransport sseTransport;
    @Mock private ChatMessageSanitizer sanitizer;
    @Mock private ChatHistoryCompactor compactor;
    @Mock private SystemPromptBuilder promptBuilder;
    @Mock private StreamingToolCoordinator toolCoordinator;
    @Mock private ConfirmationBlockParser confirmationParser;
    @Mock private DirectOpenAiModelClient directModelClient;
    @Mock private SessionPostProcessor postProcessor;
    @Mock private TimeoutFallbackHandler timeoutFallbackHandler;
    @Mock private IntermediateResponseStreamer intermediateResponseStreamer;

    @Mock private StreamingChatModel primaryModel;
    @Mock private StreamingChatModel fallbackModel;

    private StreamingChatEngine engine;

    @BeforeEach
    void setUp() {
        engine = new StreamingChatEngine(
                sessionRepository,
                messageRepository,
                routingService,
                aiLogService,
                chatStreamStatusService,
                sessionChatMemoryService,
                toolCallingRegistryService,
                thinkingNarratorService,
                tokenCountEstimator,
                objectMapper,
                sseTransport,
                sanitizer,
                compactor,
                promptBuilder,
                toolCoordinator,
                confirmationParser,
                directModelClient,
                postProcessor,
                timeoutFallbackHandler,
                intermediateResponseStreamer
        );
        ReflectionTestUtils.setField(engine, "streamFirstResponseTimeoutSeconds", 60);
        ReflectionTestUtils.setField(engine, "maxOutputTokens", 3500);
    }

    @Test
    @DisplayName("streamRound should invoke fallback once when primary returns retryable 503")
    void streamRound_shouldInvokeFallbackOnce_whenPrimaryReturnsRetryable503() {
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(890L);

        // Sanitize & compactor stubs
        dev.langchain4j.data.message.UserMessage userMsg = dev.langchain4j.data.message.UserMessage.from("Hello");
        when(sanitizer.cleanAndAlternateRoles(any(), anyBoolean())).thenReturn(new ArrayList<>(java.util.List.of(userMsg)));
        when(compactor.compactHistoryForRequest(any(), any())).thenReturn(new ArrayList<>(java.util.List.of(userMsg)));
        when(sanitizer.sanitizeHistoryForTools(any())).thenReturn(new ArrayList<>(java.util.List.of(userMsg)));

        // SSE transport stub: successful send returns true
        when(sseTransport.safeSend(any(), any(), any(), any())).thenReturn(true);

        // Timeout handler stub: primary model has no remaining API keys
        when(timeoutFallbackHandler.hasRemainingKeys(eq(primaryModel), anyInt())).thenReturn(false);

        // Routing service stubs: primary model has fallback, which resolves to fallbackModel
        when(routingService.hasStreamingFallbackAfter(primaryModel)).thenReturn(true);
        when(routingService.getNextStreamingFallback(primaryModel)).thenReturn(fallbackModel);
        when(routingService.getModelName(fallbackModel)).thenReturn("gemini-2.5-flash");
        when(routingService.hasStreamingFallbackAfter(fallbackModel)).thenReturn(false);

        // Confirmation & sanitizer stubs for fallback response
        when(confirmationParser.appendTaskPilotBlocks(any(), any())).thenReturn("Fallback answer");
        when(sanitizer.stripToolCallJson(any())).thenReturn("Fallback answer");
        when(sanitizer.stripThinkBlocks(any())).thenReturn("Fallback answer");
        when(sanitizer.extractAllThinkBlocks(any())).thenReturn("");

        // Primary model fails synchronously with retryable 503 error
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onError(new RuntimeException("OpenAI stream request failed",
                    new RuntimeException("com.openai.errors.InternalServerException: 503: Service Unavailable")));
            return null;
        }).when(primaryModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        // Fallback model succeeds
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("Fallback answer");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("Fallback answer"))
                    .build());
            return null;
        }).when(fallbackModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        // Execute streaming round
        engine.doStream(
                emitter,
                emitterCompleted,
                session,
                890L,
                3L,
                "Hello",
                new ArrayList<>(),
                "System prompt",
                primaryModel,
                "gemini-3.5-flash",
                System.currentTimeMillis(),
                false,
                "msg-890",
                false,
                false,
                0
        );

        // Verify:
        // 1. Primary model was invoked exactly once
        verify(primaryModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        // 2. Fallback resolver was asked for fallback after primary error
        verify(routingService, times(1)).getNextStreamingFallback(primaryModel);

        // 3. Fallback model was invoked exactly once
        verify(fallbackModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        // 4. Terminal failure branch was NOT entered solely from the recoverable primary error
        verify(chatStreamStatusService, never()).updatePhase(any(), any(), eq(Phase.FAILED), any(), any(), any());
        verify(sseTransport, never()).safeSend(any(), eq("phase"), eq(Phase.FAILED.name()), any());
        verify(sseTransport, never()).safeSend(any(), eq("error"), any(), any());

        // 5. Successful fallback followed finalization behavior
        verify(sseTransport, times(1)).safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
        verify(sseTransport, times(1)).safeSend(emitter, "done", "Fallback answer", null);
        verify(sseTransport, times(1)).safeComplete(emitter, emitterCompleted);
    }

    @Test
    @DisplayName("streamRound should invoke fallback once using real SmartRoutingService composition")
    void streamRound_shouldInvokeFallbackOnce_withRealRoutingServiceComposition() {
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(890L);

        // Sanitize & compactor stubs
        dev.langchain4j.data.message.UserMessage userMsg = dev.langchain4j.data.message.UserMessage.from("Hello");
        when(sanitizer.cleanAndAlternateRoles(any(), anyBoolean())).thenReturn(new ArrayList<>(List.of(userMsg)));
        when(compactor.compactHistoryForRequest(any(), any())).thenReturn(new ArrayList<>(List.of(userMsg)));
        when(sanitizer.sanitizeHistoryForTools(any())).thenReturn(new ArrayList<>(List.of(userMsg)));
        when(sseTransport.safeSend(any(), any(), any(), any())).thenReturn(true);
        when(confirmationParser.appendTaskPilotBlocks(any(), any())).thenReturn("Fallback answer");
        when(sanitizer.stripToolCallJson(any())).thenReturn("Fallback answer");
        when(sanitizer.stripThinkBlocks(any())).thenReturn("Fallback answer");
        when(sanitizer.extractAllThinkBlocks(any())).thenReturn("");

        // Construct real SmartRoutingService without Spring context
        SystemSettingPort systemSettingPort = mock(SystemSettingPort.class);
        AiModelConfig aiModelConfig = mock(AiModelConfig.class);

        when(aiModelConfig.getModelProvider(primaryModel)).thenReturn("GEMINI");
        when(aiModelConfig.getModelName(primaryModel)).thenReturn("gemini-3.5-flash");
        when(aiModelConfig.getModelProvider(fallbackModel)).thenReturn("GEMINI");
        when(aiModelConfig.getModelName(fallbackModel)).thenReturn("gemini-2.5-flash");
        when(aiModelConfig.getOrCreateDynamicModel(eq("GEMINI"), eq("gemini-2.5-flash"), any())).thenReturn(fallbackModel);

        Map<String, Object> dbPriority = Map.of("models", List.of(
                Map.of("provider", "GEMINI", "model", "gemini-3.5-flash"),
                Map.of("provider", "GEMINI", "model", "gemini-2.5-flash")
        ));
        when(systemSettingPort.findJsonObjectByKey("ai.model_priority")).thenReturn(Optional.of(dbPriority));

        SmartRoutingService realRoutingService = new SmartRoutingService(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, systemSettingPort, aiModelConfig
        );

        StreamingChatEngine engineWithRealRouting = new StreamingChatEngine(
                sessionRepository,
                messageRepository,
                realRoutingService,
                aiLogService,
                chatStreamStatusService,
                sessionChatMemoryService,
                toolCallingRegistryService,
                thinkingNarratorService,
                tokenCountEstimator,
                objectMapper,
                sseTransport,
                sanitizer,
                compactor,
                promptBuilder,
                toolCoordinator,
                confirmationParser,
                directModelClient,
                postProcessor,
                timeoutFallbackHandler,
                intermediateResponseStreamer
        );
        ReflectionTestUtils.setField(engineWithRealRouting, "streamFirstResponseTimeoutSeconds", 60);
        ReflectionTestUtils.setField(engineWithRealRouting, "maxOutputTokens", 3500);

        when(timeoutFallbackHandler.hasRemainingKeys(eq(primaryModel), anyInt())).thenReturn(false);

        // Primary fails with realistic 503 error
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onError(new RuntimeException("OpenAI stream request failed",
                    new RuntimeException("com.openai.errors.InternalServerException: 503: Service Unavailable")));
            return null;
        }).when(primaryModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        // Fallback succeeds
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("Fallback answer");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("Fallback answer"))
                    .build());
            return null;
        }).when(fallbackModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        engineWithRealRouting.doStream(
                emitter,
                emitterCompleted,
                session,
                890L,
                3L,
                "Hello",
                new ArrayList<>(),
                "System prompt",
                primaryModel,
                "gemini-3.5-flash",
                System.currentTimeMillis(),
                false,
                "msg-890",
                false,
                false,
                0
        );

        // Verify fallback model was called via REAL SmartRoutingService catalog navigation
        verify(primaryModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(fallbackModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(sseTransport, times(1)).safeSend(emitter, "phase", Phase.FINALIZED.name(), null);
        verify(sseTransport, times(1)).safeSend(emitter, "done", "Fallback answer", null);
        verify(sseTransport, times(1)).safeComplete(emitter, emitterCompleted);
    }
}
