package com.taskpilot.ai.streaming.engine;

import com.taskpilot.ai.context.ChatHistoryCompactor;
import com.taskpilot.ai.context.ChatMessageSanitizer;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.service.ChatStreamStatusService;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.ai.streaming.diagnostic.FinalizerDiagnosticOutcome;
import com.taskpilot.ai.streaming.diagnostic.FinalizerTerminalCause;
import com.taskpilot.ai.streaming.postprocess.SessionPostProcessor;
import com.taskpilot.ai.streaming.sse.AiSseTransport;
import com.taskpilot.ai.streaming.testutil.ControllableStreamingChatModel;
import com.taskpilot.ai.streaming.testutil.DeterministicTestScheduler;
import com.taskpilot.ai.streaming.tool.ConfirmationBlockParser;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TimeoutFallbackHandlerDeterministicTest {

    @Mock private AiSseTransport sseTransport;
    @Mock private ChatStreamStatusService chatStreamStatusService;
    @Mock private SmartRoutingService routingService;
    @Mock private ChatMessageSanitizer sanitizer;
    @Mock private ChatHistoryCompactor compactor;
    @Mock private ConfirmationBlockParser confirmationParser;
    @Mock private SessionPostProcessor postProcessor;

    private TimeoutFallbackHandler handler;
    private DeterministicTestScheduler scheduler;
    private ControllableStreamingChatModel fakeModel;

    @BeforeEach
    void setUp() {
        scheduler = new DeterministicTestScheduler();
        fakeModel = new ControllableStreamingChatModel();

        handler = new TimeoutFallbackHandler(
                sseTransport,
                chatStreamStatusService,
                routingService,
                sanitizer,
                compactor,
                confirmationParser,
                postProcessor,
                scheduler
        );

        when(routingService.getModelName(fakeModel)).thenReturn("fake-finalizer-model");
        when(routingService.getModelProvider(fakeModel)).thenReturn("FAKE_PROVIDER");
        when(sanitizer.sanitizeHistoryForTools(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sanitizer.cleanAndAlternateRoles(any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        when(compactor.compactHistoryForRequest(any(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(sanitizer.stripThinkBlocks(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sanitizer.stripToolCallJson(any())).thenAnswer(inv -> inv.getArgument(0));
        when(confirmationParser.appendTaskPilotBlocks(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static final String EXPECTED_TERMINAL_DEGRADATION =
            "I retrieved the requested data, but the AI service could not finish formatting the response. Please try again shortly.";

    @Test
    @DisplayName("Test E: Finalizer remains silent -> 25s watchdog wins, fallback text finalized, late callbacks ignored")
    void testE_finalizerRemainsSilent_watchdogWinsOnce() {
        AtomicReference<FinalizerDiagnosticOutcome> capturedOutcome = new AtomicReference<>();
        handler.setDiagnosticOutcomeListenerForTesting(capturedOutcome::set);

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = mock(ChatSessionEntity.class);

        handler.forceTextOnlyResponse(
                emitter, emitterCompleted, session, 891L, 18L, "ok lấy thông tin",
                new ArrayList<>(List.of(new SystemMessage("sys"), new UserMessage("user"))),
                "sys", fakeModel, "fake-finalizer-model", System.currentTimeMillis(),
                false, "client-msg-e", new StringBuilder(),
                new AtomicBoolean(false), new AtomicBoolean(false), false,
                List.of(), new LinkedHashSet<>(List.of("smartQuery")), "guardrail"
        );

        assertThat(fakeModel.getInvocationCount()).isEqualTo(1);
        assertThat(scheduler.getPendingTaskCount()).isEqualTo(1);

        // Advance virtual time to 25 seconds
        scheduler.advanceTime(25, TimeUnit.SECONDS);

        assertThat(capturedOutcome.get()).isNotNull();
        assertThat(capturedOutcome.get().terminalCause())
                .isEqualTo(FinalizerTerminalCause.FINALIZER_TIMEOUT_BEFORE_FIRST_TOKEN);
        assertThat(capturedOutcome.get().watchdogWon()).isTrue();
        assertThat(capturedOutcome.get().finalizationExecuted()).isTrue();
        assertThat(capturedOutcome.get().partialChars()).isEqualTo(0);

        // Verify fallback text persistence exactly once
        ArgumentCaptor<String> respCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> tokenCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);

        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(
                eq(session), eq(891L), eq(18L), eq("ok lấy thông tin"), eq("sys"),
                respCaptor.capture(), any(), any(), any(), eq("fake-finalizer-model"),
                tokenCaptor.capture(), anyLong(), eq("client-msg-e"), errorCaptor.capture()
        );

        assertThat(respCaptor.getValue()).isEqualTo(EXPECTED_TERMINAL_DEGRADATION);
        // Verify token count formula: 118 / 4 = 29
        assertThat(tokenCaptor.getValue()).isEqualTo(29);
        assertThat(errorCaptor.getValue()).isEqualTo("terminalCause=FINALIZER_TIMEOUT_BEFORE_FIRST_TOKEN");

        // Verify SSE safe complete called once
        verify(sseTransport, times(1)).safeComplete(eq(emitter), any());

        // Late callbacks after watchdog
        fakeModel.emitPartial("Late token");
        fakeModel.complete("Late complete");
        fakeModel.fail(new IOException("Late error"));

        // No second persistence, no second complete, and no late SSE token emissions
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyLong(), any(), any());
        verify(sseTransport, times(1)).safeComplete(eq(emitter), any());
        verify(sseTransport, never()).sendTokenToClient(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Test F: Finalizer fails before any token -> static fallback finalized immediately with sanitized error summary")
    void testF_finalizerFailsBeforeFirstToken_immediateFallback() {
        AtomicReference<FinalizerDiagnosticOutcome> capturedOutcome = new AtomicReference<>();
        handler.setDiagnosticOutcomeListenerForTesting(capturedOutcome::set);

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = mock(ChatSessionEntity.class);

        handler.forceTextOnlyResponse(
                emitter, emitterCompleted, session, 891L, 18L, "user input",
                new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", fakeModel, "fake-finalizer-model", System.currentTimeMillis(),
                false, "client-msg-f", new StringBuilder(),
                new AtomicBoolean(false), new AtomicBoolean(false), false,
                List.of(), new LinkedHashSet<>(List.of("smartQuery")), "guardrail"
        );

        // Fail immediately at t = 0
        fakeModel.fail(new IOException("Upstream 503 Service Unavailable"));

        assertThat(capturedOutcome.get()).isNotNull();
        assertThat(capturedOutcome.get().terminalCause())
                .isEqualTo(FinalizerTerminalCause.FINALIZER_MODEL_ERROR_BEFORE_FIRST_TOKEN);
        assertThat(capturedOutcome.get().watchdogWon()).isFalse();
        assertThat(capturedOutcome.get().finalizationExecuted()).isTrue();

        ArgumentCaptor<String> respCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(
                any(), any(), any(), any(), any(), respCaptor.capture(), any(), any(), any(), any(), anyInt(), anyLong(), any(), errorCaptor.capture()
        );

        assertThat(respCaptor.getValue()).isEqualTo(EXPECTED_TERMINAL_DEGRADATION);
        assertThat(errorCaptor.getValue())
                .isEqualTo("terminalCause=FINALIZER_MODEL_ERROR_BEFORE_FIRST_TOKEN; exceptionClass=java.io.IOException");

        // Advancing time to 25s must not trigger watchdog again
        scheduler.advanceTime(25, TimeUnit.SECONDS);
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("Test G: Finalizer emits partial content, then calls onError -> classified as FINALIZER_MODEL_ERROR_AFTER_PARTIAL")
    void testG_finalizerEmitsPartialThenCallsOnError() {
        AtomicReference<FinalizerDiagnosticOutcome> capturedOutcome = new AtomicReference<>();
        handler.setDiagnosticOutcomeListenerForTesting(capturedOutcome::set);

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = mock(ChatSessionEntity.class);

        handler.forceTextOnlyResponse(
                emitter, emitterCompleted, session, 891L, 18L, "user input",
                new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", fakeModel, "fake-finalizer-model", System.currentTimeMillis(),
                false, "client-msg-g", new StringBuilder(),
                new AtomicBoolean(false), new AtomicBoolean(false), false,
                List.of(), new LinkedHashSet<>(List.of("smartQuery")), "guardrail"
        );

        // Partial token emitted
        fakeModel.emitPartial("Chào bạn, tôi đang tổng hợp...");

        // Then model fails
        fakeModel.fail(new RuntimeException("Stream terminated abruptly"));

        assertThat(capturedOutcome.get()).isNotNull();
        assertThat(capturedOutcome.get().terminalCause())
                .isEqualTo(FinalizerTerminalCause.FINALIZER_MODEL_ERROR_AFTER_PARTIAL);
        assertThat(capturedOutcome.get().partialChars()).isGreaterThan(0);

        // Under current behavior: partial buffer is discarded on error, and fallback text is finalized
        ArgumentCaptor<String> respCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(
                any(), any(), any(), any(), any(), respCaptor.capture(), any(), any(), any(), any(), anyInt(), anyLong(), any(), errorCaptor.capture()
        );
        assertThat(respCaptor.getValue()).isEqualTo(EXPECTED_TERMINAL_DEGRADATION);
        assertThat(errorCaptor.getValue())
                .isEqualTo("terminalCause=FINALIZER_MODEL_ERROR_AFTER_PARTIAL; exceptionClass=java.lang.RuntimeException");
    }

    @Test
    @DisplayName("Test H: Finalizer emits first token at t=5s, completes at t=30s -> watchdog returns without fallback, normal completion succeeds")
    void testH_finalizerEmitsTokenBefore25sCompletesAfter25s() {
        AtomicReference<FinalizerDiagnosticOutcome> capturedOutcome = new AtomicReference<>();
        handler.setDiagnosticOutcomeListenerForTesting(capturedOutcome::set);

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = mock(ChatSessionEntity.class);

        handler.forceTextOnlyResponse(
                emitter, emitterCompleted, session, 891L, 18L, "user input",
                new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", fakeModel, "fake-finalizer-model", System.currentTimeMillis(),
                false, "client-msg-h", new StringBuilder(),
                new AtomicBoolean(false), new AtomicBoolean(false), false,
                List.of(), new LinkedHashSet<>(List.of("smartQuery")), "guardrail"
        );

        // Advance virtual time to 5 seconds and emit first token
        scheduler.advanceTime(5, TimeUnit.SECONDS);
        fakeModel.emitPartial("Đây là danh sách dự án của bạn: 1. Project A");

        // Advance virtual time past the 25-second mark (to 26 seconds total)
        scheduler.advanceTime(21, TimeUnit.SECONDS);

        // Watchdog ran, but saw firstModelSignalReceived == true, so it did NOT finalize fallback!
        verify(postProcessor, never()).saveSessionMessagesAndLogsAsync(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyLong(), any(), any());

        // Now at t = 30s total (advance 4s more), model completes normally
        scheduler.advanceTime(4, TimeUnit.SECONDS);
        fakeModel.emitPartial(" - Hoàn tất.");
        fakeModel.complete("Đây là danh sách dự án của bạn: 1. Project A - Hoàn tất.");

        assertThat(capturedOutcome.get()).isNotNull();
        assertThat(capturedOutcome.get().terminalCause())
                .isEqualTo(FinalizerTerminalCause.FINALIZER_COMPLETED_WITH_TEXT);
        assertThat(capturedOutcome.get().watchdogWon()).isFalse();

        // Normal model completion persisted
        ArgumentCaptor<String> respCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(
                any(), any(), any(), any(), any(), respCaptor.capture(), any(), any(), any(), any(), anyInt(), anyLong(), any(), errCaptor.capture()
        );
        assertThat(respCaptor.getValue()).contains("Project A - Hoàn tất");
        assertThat(errCaptor.getValue()).isNull();
    }

    @Test
    @DisplayName("Test I: Finalizer throws synchronous Exception -> watchdog cancelled, static fallback finalized with FINALIZER_SYNC_START_FAILURE")
    void testI_finalizer_syncStartFailure_runsFinalizationImmediately() {
        AtomicReference<FinalizerDiagnosticOutcome> capturedOutcome = new AtomicReference<>();
        handler.setDiagnosticOutcomeListenerForTesting(capturedOutcome::set);

        fakeModel.setSyncExceptionToThrow(new RuntimeException("Simulated synchronous finalizer invocation failure"));

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = mock(ChatSessionEntity.class);

        handler.forceTextOnlyResponse(
                emitter, emitterCompleted, session, 891L, 18L, "user input",
                new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", fakeModel, "fake-finalizer-model", System.currentTimeMillis(),
                false, "client-msg-sync", new StringBuilder(),
                new AtomicBoolean(false), new AtomicBoolean(false), false,
                List.of(), new LinkedHashSet<>(List.of("smartQuery")), "guardrail"
        );

        assertThat(capturedOutcome.get()).isNotNull();
        assertThat(capturedOutcome.get().terminalCause())
                .isEqualTo(FinalizerTerminalCause.FINALIZER_SYNC_START_FAILURE);
        assertThat(capturedOutcome.get().watchdogWon()).isFalse();
        assertThat(capturedOutcome.get().finalizationExecuted()).isTrue();

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyLong(), any(), errorCaptor.capture()
        );
        assertThat(errorCaptor.getValue())
                .isEqualTo("terminalCause=FINALIZER_SYNC_START_FAILURE; exceptionClass=java.lang.RuntimeException");

        // Watchdog was cancelled
        assertThat(scheduler.getPendingTaskCount()).isEqualTo(0);
    }

    static class RateLimitException extends RuntimeException {
        RateLimitException(String message) {
            super(message);
        }
    }

    @Test
    @DisplayName("Test A: RateLimit before token, fallback succeeds")
    void testA_rateLimitBeforeToken_fallbackSucceeds() {
        ControllableStreamingChatModel fallbackModel = new ControllableStreamingChatModel();
        when(routingService.getModelName(fallbackModel)).thenReturn("fake-fallback-model");
        when(routingService.getModelProvider(fallbackModel)).thenReturn("FAKE_FALLBACK_PROVIDER");
        when(routingService.getNextStreamingFallback(fakeModel)).thenReturn(fallbackModel);

        List<FinalizerDiagnosticOutcome> capturedOutcomes = new ArrayList<>();
        handler.setDiagnosticOutcomeListenerForTesting(capturedOutcomes::add);

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = mock(ChatSessionEntity.class);

        handler.forceTextOnlyResponse(
                emitter, emitterCompleted, session, 891L, 18L, "ok lấy thông tin",
                new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", fakeModel, "fake-finalizer-model", System.currentTimeMillis(),
                false, "client-msg-a", new StringBuilder(),
                new AtomicBoolean(false), new AtomicBoolean(false), false,
                List.of(), new LinkedHashSet<>(List.of("smartQuery")), "guardrail"
        );

        // Attempt 1 fails before token with CompletionException wrapping RateLimitException
        fakeModel.fail(new java.util.concurrent.CompletionException(new RateLimitException("com.openai.errors.RateLimitException: 429: null")));

        // Attempt 2 completes successfully
        fallbackModel.emitPartial("Dữ liệu dự án từ fallback model");
        fallbackModel.complete("Dữ liệu dự án từ fallback model");

        // Verify:
        // - Attempt 1 invoked once
        assertThat(fakeModel.getInvocationCount()).isEqualTo(1);
        // - getNextStreamingFallback invoked once
        verify(routingService, times(1)).getNextStreamingFallback(fakeModel);
        // - Attempt 2 invoked once
        assertThat(fallbackModel.getInvocationCount()).isEqualTo(1);

        // - successful Attempt 2 text is persisted, static fallback text is not persisted, diagnostic error is null
        ArgumentCaptor<String> respCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(
                eq(session), eq(891L), eq(18L), eq("ok lấy thông tin"), eq("sys"),
                respCaptor.capture(), any(), any(), any(), eq("fake-fallback-model"),
                anyInt(), anyLong(), eq("client-msg-a"), errCaptor.capture()
        );
        assertThat(respCaptor.getValue()).contains("Dữ liệu dự án từ fallback model");
        assertThat(respCaptor.getValue()).doesNotContain(EXPECTED_TERMINAL_DEGRADATION);
        assertThat(errCaptor.getValue()).isNull();

        // - SSE done emitted once
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("done"), eq(respCaptor.getValue()), any());
        // - emitter completed once
        verify(sseTransport, times(1)).safeComplete(eq(emitter), any());

        // - no third attempt
        assertThat(capturedOutcomes).hasSize(2);
        assertThat(capturedOutcomes.get(0).attempt()).isEqualTo(1);
        assertThat(capturedOutcomes.get(0).finalizationExecuted()).isFalse();
        assertThat(capturedOutcomes.get(1).attempt()).isEqualTo(2);
        assertThat(capturedOutcomes.get(1).finalizationExecuted()).isTrue();
    }

    @Test
    @DisplayName("Test B: RateLimit before token, fallback also fails")
    void testB_rateLimitBeforeToken_fallbackAlsoFails() {
        ControllableStreamingChatModel fallbackModel = new ControllableStreamingChatModel();
        when(routingService.getModelName(fallbackModel)).thenReturn("fake-fallback-model");
        when(routingService.getModelProvider(fallbackModel)).thenReturn("FAKE_FALLBACK_PROVIDER");
        when(routingService.getNextStreamingFallback(fakeModel)).thenReturn(fallbackModel);

        List<FinalizerDiagnosticOutcome> capturedOutcomes = new ArrayList<>();
        handler.setDiagnosticOutcomeListenerForTesting(capturedOutcomes::add);

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = mock(ChatSessionEntity.class);

        handler.forceTextOnlyResponse(
                emitter, emitterCompleted, session, 891L, 18L, "ok lấy thông tin",
                new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", fakeModel, "fake-finalizer-model", System.currentTimeMillis(),
                false, "client-msg-b", new StringBuilder(),
                new AtomicBoolean(false), new AtomicBoolean(false), false,
                List.of(), new LinkedHashSet<>(List.of("smartQuery")), "guardrail"
        );

        // Attempt 1 fails with 429
        fakeModel.fail(new java.util.concurrent.CompletionException(new RateLimitException("429 Too Many Requests")));

        // Attempt 2 also fails before token
        fallbackModel.fail(new IllegalStateException("Fallback upstream outage"));

        // Verify:
        // - exactly two logical model attempts, no third attempt
        assertThat(fakeModel.getInvocationCount()).isEqualTo(1);
        assertThat(fallbackModel.getInvocationCount()).isEqualTo(1);
        assertThat(capturedOutcomes).hasSize(2);

        // - static degradation finalized once
        // - persistence once
        ArgumentCaptor<String> respCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(
                eq(session), eq(891L), eq(18L), eq("ok lấy thông tin"), eq("sys"),
                respCaptor.capture(), any(), any(), any(), eq("fake-fallback-model"),
                anyInt(), anyLong(), eq("client-msg-b"), errCaptor.capture()
        );
        assertThat(respCaptor.getValue()).isEqualTo(EXPECTED_TERMINAL_DEGRADATION);

        // - final diagnostic reflects the actionable fallback failure class
        assertThat(errCaptor.getValue())
                .isEqualTo("terminalCause=FINALIZER_MODEL_ERROR_BEFORE_FIRST_TOKEN; exceptionClass=java.lang.IllegalStateException");

        // - SSE done once, emitter completion once
        verify(sseTransport, times(1)).safeSend(eq(emitter), eq("done"), any(), any());
        verify(sseTransport, times(1)).safeComplete(eq(emitter), any());
    }

    @Test
    @DisplayName("Test C: Attempt 1 emits partial token then rate-limit error")
    void testC_attempt1EmitsPartialTokenThenRateLimitError_noFallback() {
        ControllableStreamingChatModel fallbackModel = new ControllableStreamingChatModel();
        when(routingService.getNextStreamingFallback(fakeModel)).thenReturn(fallbackModel);

        List<FinalizerDiagnosticOutcome> capturedOutcomes = new ArrayList<>();
        handler.setDiagnosticOutcomeListenerForTesting(capturedOutcomes::add);

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = mock(ChatSessionEntity.class);

        handler.forceTextOnlyResponse(
                emitter, emitterCompleted, session, 891L, 18L, "ok lấy thông tin",
                new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", fakeModel, "fake-finalizer-model", System.currentTimeMillis(),
                false, "client-msg-c", new StringBuilder(),
                new AtomicBoolean(false), new AtomicBoolean(false), false,
                List.of(), new LinkedHashSet<>(List.of("smartQuery")), "guardrail"
        );

        // Partial token emitted first
        fakeModel.emitPartial("Một phần câu trả lời...");

        // Then rate limit error
        fakeModel.fail(new RateLimitException("429 rate limit exceeded"));

        // Verify:
        // - getNextStreamingFallback is never called
        verify(routingService, never()).getNextStreamingFallback(any());
        // - Attempt 2 is never invoked
        assertThat(fallbackModel.getInvocationCount()).isEqualTo(0);

        // - static degradation behavior remains
        // - no mixed-model output
        // - exact-once persistence and completion
        ArgumentCaptor<String> respCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(
                eq(session), eq(891L), eq(18L), eq("ok lấy thông tin"), eq("sys"),
                respCaptor.capture(), any(), any(), any(), eq("fake-finalizer-model"),
                anyInt(), anyLong(), eq("client-msg-c"), errCaptor.capture()
        );
        assertThat(respCaptor.getValue()).isEqualTo(EXPECTED_TERMINAL_DEGRADATION);
        assertThat(errCaptor.getValue())
                .isEqualTo("terminalCause=FINALIZER_MODEL_ERROR_AFTER_PARTIAL; exceptionClass=com.taskpilot.ai.streaming.engine.TimeoutFallbackHandlerDeterministicTest$RateLimitException");

        verify(sseTransport, times(1)).safeComplete(eq(emitter), any());
        assertThat(capturedOutcomes).hasSize(1);
    }

    @Test
    @DisplayName("Test D: No fallback candidate exists (returns currentModel)")
    void testD_noFallbackCandidateExists_degradesImmediately() {
        when(routingService.getNextStreamingFallback(fakeModel)).thenReturn(fakeModel);

        List<FinalizerDiagnosticOutcome> capturedOutcomes = new ArrayList<>();
        handler.setDiagnosticOutcomeListenerForTesting(capturedOutcomes::add);

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = mock(ChatSessionEntity.class);

        handler.forceTextOnlyResponse(
                emitter, emitterCompleted, session, 891L, 18L, "ok lấy thông tin",
                new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", fakeModel, "fake-finalizer-model", System.currentTimeMillis(),
                false, "client-msg-d", new StringBuilder(),
                new AtomicBoolean(false), new AtomicBoolean(false), false,
                List.of(), new LinkedHashSet<>(List.of("smartQuery")), "guardrail"
        );

        fakeModel.fail(new RateLimitException("429 Too Many Requests"));

        // Verify:
        // - no repeated current-model invocation
        // - no recursion
        assertThat(fakeModel.getInvocationCount()).isEqualTo(1);
        verify(routingService, times(1)).getNextStreamingFallback(fakeModel);

        // - static degradation once
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyLong(), any(), any());
        verify(sseTransport, times(1)).safeComplete(eq(emitter), any());
        assertThat(capturedOutcomes).hasSize(1);
        assertThat(capturedOutcomes.get(0).finalizationExecuted()).isTrue();
    }

    @Test
    @DisplayName("Test E: Non-rate-limit error before token")
    void testE_nonRateLimitErrorBeforeToken_noFallback() {
        ControllableStreamingChatModel fallbackModel = new ControllableStreamingChatModel();
        when(routingService.getNextStreamingFallback(fakeModel)).thenReturn(fallbackModel);

        List<FinalizerDiagnosticOutcome> capturedOutcomes = new ArrayList<>();
        handler.setDiagnosticOutcomeListenerForTesting(capturedOutcomes::add);

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        ChatSessionEntity session = mock(ChatSessionEntity.class);

        handler.forceTextOnlyResponse(
                emitter, emitterCompleted, session, 891L, 18L, "ok lấy thông tin",
                new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", fakeModel, "fake-finalizer-model", System.currentTimeMillis(),
                false, "client-msg-non-429", new StringBuilder(),
                new AtomicBoolean(false), new AtomicBoolean(false), false,
                List.of(), new LinkedHashSet<>(List.of("smartQuery")), "guardrail"
        );

        // Non-rate-limit error: 503 or generic IOException
        fakeModel.fail(new IOException("Connection reset by peer"));

        // Verify:
        // - no next-model fallback
        verify(routingService, never()).getNextStreamingFallback(any());
        assertThat(fallbackModel.getInvocationCount()).isEqualTo(0);

        // - current degradation behavior remains
        // - exact-once terminal handling
        verify(postProcessor, times(1)).saveSessionMessagesAndLogsAsync(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyLong(), any(), any());
        verify(sseTransport, times(1)).safeComplete(eq(emitter), any());
        assertThat(capturedOutcomes).hasSize(1);
    }
}
