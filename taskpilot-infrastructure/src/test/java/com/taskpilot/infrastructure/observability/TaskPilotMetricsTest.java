package com.taskpilot.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPilotMetricsTest {

    private SimpleMeterRegistry registry;
    private TaskPilotMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new TaskPilotMetrics(registry, ObservationRegistry.NOOP);
    }

    @Test
    void normalizeAiProvider_boundsArbitraryModelSlugsToFourLowCardinalityValues() {
        assertThat(TaskPilotMetrics.normalizeAiProvider("gemini-3.5-flash")).isEqualTo("gemini");
        assertThat(TaskPilotMetrics.normalizeAiProvider("gemma-4-27b")).isEqualTo("gemini");
        assertThat(TaskPilotMetrics.normalizeAiProvider("openai/gpt-oss-120b")).isEqualTo("groq");
        assertThat(TaskPilotMetrics.normalizeAiProvider("groq")).isEqualTo("groq");
        assertThat(TaskPilotMetrics.normalizeAiProvider("nvidia/nemotron-3-super-120b-a12b:free")).isEqualTo("openrouter");
        assertThat(TaskPilotMetrics.normalizeAiProvider("google/gemma-4-26b-a4b-it:free")).isEqualTo("openrouter");
        assertThat(TaskPilotMetrics.normalizeAiProvider("some-random-user-supplied-slug-12345")).isEqualTo("other");
        assertThat(TaskPilotMetrics.normalizeAiProvider(null)).isEqualTo("other");
    }

    @Test
    void recordAiMetrics_recordsCompletionTokensDurationFallbackAndFailure() {
        metrics.recordAiCompletion("gemini-2.5-flash", 1250, 820L);
        metrics.recordAiFallback("gemini-2.5-flash");
        metrics.recordAiFailure("nvidia/nemotron:free", 1500L);
        metrics.recordAiAborted("openai/gpt-oss-120b");

        Counter geminiSuccess = registry.find("taskpilot.ai.requests")
                .tag("provider", "gemini").tag("outcome", "success").counter();
        Counter geminiTokens = registry.find("taskpilot.ai.tokens")
                .tag("provider", "gemini").counter();
        Timer geminiDuration = registry.find("taskpilot.ai.request.duration")
                .tag("provider", "gemini").tag("outcome", "success").timer();
        Counter geminiFallback = registry.find("taskpilot.ai.requests")
                .tag("provider", "gemini").tag("outcome", "fallback").counter();
        Counter openRouterError = registry.find("taskpilot.ai.requests")
                .tag("provider", "openrouter").tag("outcome", "error").counter();
        Counter groqAborted = registry.find("taskpilot.ai.requests")
                .tag("provider", "groq").tag("outcome", "aborted").counter();

        assertThat(geminiSuccess).isNotNull();
        assertThat(geminiSuccess.count()).isEqualTo(1.0);
        assertThat(geminiTokens).isNotNull();
        assertThat(geminiTokens.count()).isEqualTo(1250.0);
        assertThat(geminiDuration).isNotNull();
        assertThat(geminiDuration.count()).isEqualTo(1L);
        assertThat(geminiDuration.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(820.0);
        assertThat(geminiFallback).isNotNull();
        assertThat(geminiFallback.count()).isEqualTo(1.0);
        assertThat(openRouterError).isNotNull();
        assertThat(openRouterError.count()).isEqualTo(1.0);
        assertThat(groqAborted).isNotNull();
        assertThat(groqAborted.count()).isEqualTo(1.0);
    }

    @Test
    void recordEgressAndWebhookMetrics_sanitizesUnboundedValues() {
        metrics.recordEgressRecording("cloud", "started");
        metrics.recordEgressRecording("cloud", "completed");
        metrics.recordEgressRecording("fallback", "completed");
        metrics.recordEgressRecording("untrusted_mode", "untrusted_outcome");

        metrics.recordLiveKitWebhook("egress_ended", "accepted");
        metrics.recordLiveKitWebhook("room_custom_99999", "accepted");
        metrics.recordLiveKitWebhook("invalid", "rejected");

        assertThat(registry.find("taskpilot.egress.recordings")
                .tag("mode", "cloud").tag("outcome", "started").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("taskpilot.egress.recordings")
                .tag("mode", "fallback").tag("outcome", "completed").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("taskpilot.egress.recordings")
                .tag("mode", "cloud").tag("outcome", "failed").counter().count()).isEqualTo(1.0);

        assertThat(registry.find("taskpilot.livekit.webhooks")
                .tag("event", "egress_ended").tag("outcome", "accepted").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("taskpilot.livekit.webhooks")
                .tag("event", "other").tag("outcome", "accepted").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("taskpilot.livekit.webhooks")
                .tag("event", "invalid").tag("outcome", "rejected").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordArchiveTransfer_tracksSuccessBytesDurationAndFailureOutcomes() {
        metrics.recordArchiveTransfer("success", 50_000_000L, 4200L);
        metrics.recordArchiveTransfer("integrity_mismatch", 12345L, 900L);

        assertThat(registry.find("taskpilot.archive.transfers")
                .tag("outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("taskpilot.archive.bytes")
                .tag("outcome", "success").counter().count()).isEqualTo(50_000_000.0);
        assertThat(registry.find("taskpilot.archive.transfers")
                .tag("outcome", "integrity_mismatch").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("taskpilot.archive.transfer.duration")
                .tag("outcome", "success").timer().count()).isEqualTo(1L);
        assertThat(registry.find("taskpilot.archive.transfer.duration")
                .tag("outcome", "failed").timer().count()).isEqualTo(1L);
    }

    @Test
    void webSocketSessionGauge_isIdempotentAndNeverNegative() {
        Gauge activeGauge = registry.find("taskpilot.websocket.sessions.active").gauge();
        assertThat(activeGauge).isNotNull();
        assertThat(activeGauge.value()).isEqualTo(0.0);

        metrics.onWebSocketConnected("sess-1");
        metrics.onWebSocketConnected("sess-1"); // duplicate connect must not double-count
        metrics.onWebSocketConnected("sess-2");
        assertThat(activeGauge.value()).isEqualTo(2.0);

        metrics.onWebSocketDisconnected("sess-1");
        metrics.onWebSocketDisconnected("sess-1"); // duplicate disconnect must not underflow
        metrics.onWebSocketDisconnected("unknown-session");
        assertThat(activeGauge.value()).isEqualTo(1.0);

        metrics.onWebSocketDisconnected("sess-2");
        assertThat(activeGauge.value()).isEqualTo(0.0);

        metrics.recordWebSocketMessage("sent");
        metrics.recordWebSocketMessage("auth_denied");
        assertThat(registry.find("taskpilot.websocket.messages").tag("outcome", "sent").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("taskpilot.websocket.messages").tag("outcome", "auth_denied").counter().count()).isEqualTo(1.0);
    }
}
