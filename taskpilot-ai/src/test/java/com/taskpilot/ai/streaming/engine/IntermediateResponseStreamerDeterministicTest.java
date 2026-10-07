package com.taskpilot.ai.streaming.engine;

import com.taskpilot.ai.context.ChatHistoryCompactor;
import com.taskpilot.ai.context.ChatMessageSanitizer;
import com.taskpilot.ai.service.ChatStreamStatusService;
import com.taskpilot.ai.service.SessionChatMemoryService;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.ai.streaming.diagnostic.IntermediateDiagnosticOutcome;
import com.taskpilot.ai.streaming.diagnostic.IntermediateTerminalCause;
import com.taskpilot.ai.streaming.sse.AiSseTransport;
import com.taskpilot.ai.streaming.testutil.ControllableStreamingChatModel;
import com.taskpilot.ai.streaming.testutil.DeterministicTestScheduler;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class IntermediateResponseStreamerDeterministicTest {

    @Mock private AiSseTransport sseTransport;
    @Mock private ChatMessageSanitizer sanitizer;
    @Mock private ChatHistoryCompactor compactor;
    @Mock private SmartRoutingService routingService;
    @Mock private SessionChatMemoryService sessionChatMemoryService;
    @Mock private ChatStreamStatusService chatStreamStatusService;

    private IntermediateResponseStreamer streamer;
    private DeterministicTestScheduler scheduler;
    private ControllableStreamingChatModel fakeModel;

    @BeforeEach
    void setUp() {
        streamer = new IntermediateResponseStreamer(
                sseTransport,
                sanitizer,
                compactor,
                routingService,
                sessionChatMemoryService,
                chatStreamStatusService
        );
        scheduler = new DeterministicTestScheduler();
        fakeModel = new ControllableStreamingChatModel();

        when(routingService.getReasoningTextModel()).thenReturn(fakeModel);
        when(routingService.getModelName(fakeModel)).thenReturn("fake-intermediate-model");
        when(routingService.getModelProvider(fakeModel)).thenReturn("FAKE_PROVIDER");
        when(sanitizer.sanitizeHistoryForTools(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sanitizer.cleanAndAlternateRoles(any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        when(compactor.compactHistoryForRequest(any(), anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Test A: Intermediate remains silent -> 30s watchdog wins, continuation runs once, late callbacks ignored")
    void testA_intermediateRemainsSilent_watchdogWinsOnce() {
        AtomicInteger continuationCount = new AtomicInteger(0);
        AtomicReference<IntermediateDiagnosticOutcome> capturedOutcome = new AtomicReference<>();
        streamer.setDiagnosticOutcomeListenerForTesting(capturedOutcome::set);

        SseEmitter emitter = mock(SseEmitter.class);
        StringBuilder fullResponse = new StringBuilder();

        streamer.streamIntermediateResponseAndContinue(
                emitter, 891L, "ok lấy thông tin", new ArrayList<>(List.of(new SystemMessage("sys"), new UserMessage("user"))),
                "sys", "fake-intermediate-model", "client-msg-123", fullResponse,
                new AtomicBoolean(false), new AtomicBoolean(false), "tool results data",
                scheduler, continuationCount::incrementAndGet
        );

        assertThat(fakeModel.getInvocationCount()).isEqualTo(1);
        assertThat(continuationCount.get()).isEqualTo(0);
        assertThat(scheduler.getPendingTaskCount()).isEqualTo(1);

        // Advance time to 30 seconds
        scheduler.advanceTime(30, TimeUnit.SECONDS);

        assertThat(continuationCount.get()).isEqualTo(1);
        assertThat(capturedOutcome.get()).isNotNull();
        assertThat(capturedOutcome.get().terminalCause())
                .isEqualTo(IntermediateTerminalCause.INTERMEDIATE_TIMEOUT_BEFORE_FIRST_TOKEN);
        assertThat(capturedOutcome.get().watchdogWon()).isTrue();
        assertThat(capturedOutcome.get().continuationExecuted()).isTrue();
        assertThat(capturedOutcome.get().partialChars()).isEqualTo(0);

        // Assert memory append NEVER occurred
        verify(sessionChatMemoryService, never()).appendAssistantMessage(any(), any(), any());

        // Test late callbacks are completely ignored
        fakeModel.emitPartial("Late token after watchdog");
        fakeModel.complete("Late complete after watchdog");
        fakeModel.fail(new IOException("Late error after watchdog"));

        // Continuation must still be exactly 1
        assertThat(continuationCount.get()).isEqualTo(1);
        // Memory append must still be never called
        verify(sessionChatMemoryService, never()).appendAssistantMessage(any(), any(), any());
        // fullResponse buffer must remain untouched by late callbacks
        assertThat(fullResponse.toString()).isEmpty();
    }

    @Test
    @DisplayName("Test B: Intermediate fails immediately before first token -> watchdog cancelled, continuation runs once")
    void testB_intermediateFailsImmediately_watchdogCancelled() {
        AtomicInteger continuationCount = new AtomicInteger(0);
        AtomicReference<IntermediateDiagnosticOutcome> capturedOutcome = new AtomicReference<>();
        streamer.setDiagnosticOutcomeListenerForTesting(capturedOutcome::set);

        SseEmitter emitter = mock(SseEmitter.class);
        StringBuilder fullResponse = new StringBuilder();

        streamer.streamIntermediateResponseAndContinue(
                emitter, 891L, "user input", new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", "fake-model", "client-msg-b", fullResponse,
                new AtomicBoolean(false), new AtomicBoolean(false), "tool results",
                scheduler, continuationCount::incrementAndGet
        );

        // Model fails immediately at virtual time 0
        fakeModel.fail(new IOException("Upstream 503 Service Unavailable"));

        assertThat(continuationCount.get()).isEqualTo(1);
        assertThat(capturedOutcome.get()).isNotNull();
        assertThat(capturedOutcome.get().terminalCause())
                .isEqualTo(IntermediateTerminalCause.INTERMEDIATE_MODEL_ERROR_BEFORE_FIRST_TOKEN);
        assertThat(capturedOutcome.get().watchdogWon()).isFalse();
        assertThat(capturedOutcome.get().continuationExecuted()).isTrue();
        assertThat(capturedOutcome.get().error()).isInstanceOf(IOException.class);

        // Advancing scheduler to 30s must NOT trigger watchdog again
        scheduler.advanceTime(30, TimeUnit.SECONDS);
        assertThat(continuationCount.get()).isEqualTo(1);
        verify(sessionChatMemoryService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    @DisplayName("Test C: Intermediate emits partial content, then fails -> cause includes AFTER_PARTIAL, continuation runs once")
    void testC_intermediateEmitsPartialThenFails() {
        AtomicInteger continuationCount = new AtomicInteger(0);
        AtomicReference<IntermediateDiagnosticOutcome> capturedOutcome = new AtomicReference<>();
        streamer.setDiagnosticOutcomeListenerForTesting(capturedOutcome::set);

        SseEmitter emitter = mock(SseEmitter.class);
        StringBuilder fullResponse = new StringBuilder();

        streamer.streamIntermediateResponseAndContinue(
                emitter, 891L, "user input", new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", "fake-model", "client-msg-c", fullResponse,
                new AtomicBoolean(false), new AtomicBoolean(false), "tool results",
                scheduler, continuationCount::incrementAndGet
        );

        // Emit partial content
        fakeModel.emitPartial("Tôi đã tìm thấy 5 dự án: ");
        assertThat(fullResponse.toString()).contains("Tôi đã tìm thấy 5 dự án: ");

        // Now model fails
        fakeModel.fail(new RuntimeException("Connection reset"));

        assertThat(continuationCount.get()).isEqualTo(1);
        assertThat(capturedOutcome.get()).isNotNull();
        assertThat(capturedOutcome.get().terminalCause())
                .isEqualTo(IntermediateTerminalCause.INTERMEDIATE_MODEL_ERROR_AFTER_PARTIAL);
        assertThat(capturedOutcome.get().partialChars()).isGreaterThan(0);
        assertThat(capturedOutcome.get().continuationExecuted()).isTrue();

        // Memory append was not called because error interrupted completion
        verify(sessionChatMemoryService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    @DisplayName("Test D: Intermediate completes with blank content -> classified as INTERMEDIATE_COMPLETED_EMPTY, asserts memory append")
    void testD_intermediateCompletesWithBlankContent_assertsMemoryAppend() {
        AtomicInteger continuationCount = new AtomicInteger(0);
        AtomicReference<IntermediateDiagnosticOutcome> capturedOutcome = new AtomicReference<>();
        streamer.setDiagnosticOutcomeListenerForTesting(capturedOutcome::set);

        when(sessionChatMemoryService.sanitizeAssistantMessage("")).thenReturn("");

        SseEmitter emitter = mock(SseEmitter.class);
        StringBuilder fullResponse = new StringBuilder();

        streamer.streamIntermediateResponseAndContinue(
                emitter, 891L, "user input", new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", "fake-model", "client-msg-d", fullResponse,
                new AtomicBoolean(false), new AtomicBoolean(false), "tool results",
                scheduler, continuationCount::incrementAndGet
        );

        // Model completes with empty string
        fakeModel.completeEmpty();

        assertThat(continuationCount.get()).isEqualTo(1);
        assertThat(capturedOutcome.get()).isNotNull();
        assertThat(capturedOutcome.get().terminalCause())
                .isEqualTo(IntermediateTerminalCause.INTERMEDIATE_COMPLETED_EMPTY);

        // Under current code, onComplete ALWAYS calls sessionChatMemoryService.appendAssistantMessage
        verify(sessionChatMemoryService, times(1)).appendAssistantMessage(eq(891L), eq(""), eq("sys"));
    }

    @Test
    @DisplayName("Test: Intermediate throws synchronous Exception -> watchdog cancelled, INTERMEDIATE_SYNC_START_FAILURE, continuation runs once")
    void testIntermediate_syncStartFailure_runsContinuationOnce() {
        AtomicInteger continuationCount = new AtomicInteger(0);
        AtomicReference<IntermediateDiagnosticOutcome> capturedOutcome = new AtomicReference<>();
        streamer.setDiagnosticOutcomeListenerForTesting(capturedOutcome::set);

        fakeModel.setSyncExceptionToThrow(new RuntimeException("Simulated synchronous model start failure"));

        SseEmitter emitter = mock(SseEmitter.class);
        StringBuilder fullResponse = new StringBuilder();

        streamer.streamIntermediateResponseAndContinue(
                emitter, 891L, "user input", new ArrayList<>(List.of(new SystemMessage("sys"))),
                "sys", "fake-intermediate-model", "client-msg-sync", fullResponse,
                new AtomicBoolean(false), new AtomicBoolean(false), "tool results",
                scheduler, continuationCount::incrementAndGet
        );

        // Continuation runs immediately (virtual time = 0)
        assertThat(continuationCount.get()).isEqualTo(1);
        assertThat(capturedOutcome.get()).isNotNull();
        assertThat(capturedOutcome.get().terminalCause())
                .isEqualTo(IntermediateTerminalCause.INTERMEDIATE_SYNC_START_FAILURE);
        assertThat(capturedOutcome.get().watchdogWon()).isFalse();
        assertThat(capturedOutcome.get().continuationExecuted()).isTrue();

        // Watchdog was cancelled
        assertThat(scheduler.getPendingTaskCount()).isEqualTo(0);
    }
}
