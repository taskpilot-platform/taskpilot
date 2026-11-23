package com.taskpilot.infrastructure.observability;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Centralized, strictly low-cardinality custom business metrics & Observation helper for TaskPilot.
 *
 * <p>Cardinality discipline (Grafana Cloud Free Tier = 10,000 active series):
 * NEVER pass unbounded identifiers (userId, projectId, meetingId, egressId, fileId, sessionId,
 * raw model slugs, or exception messages) as Prometheus tag values. Every tag value is normalized
 * into a small, fixed set of enum-like strings.</p>
 */
@Component
public class TaskPilotMetrics {

    private static final ContextSnapshotFactory CONTEXT_SNAPSHOT_FACTORY =
            ContextSnapshotFactory.builder().build();

    private static final Set<String> VALID_AI_PROVIDERS = Set.of("gemini", "groq", "openrouter", "other");
    private static final Set<String> VALID_EGRESS_MODES = Set.of("cloud", "fallback");
    private static final Set<String> VALID_EGRESS_OUTCOMES = Set.of(
            "started", "completed", "failed", "backup_storage", "start_failed"
    );
    private static final Set<String> VALID_WEBHOOK_EVENTS = Set.of(
            "egress_started", "egress_updated", "egress_ended", "invalid", "other"
    );
    private static final Set<String> VALID_WEBHOOK_OUTCOMES = Set.of("accepted", "rejected", "error");
    private static final Set<String> VALID_ARCHIVE_OUTCOMES = Set.of(
            "success", "failed", "integrity_mismatch", "b2_cleanup_failed"
    );
    private static final Set<String> VALID_WS_MESSAGE_OUTCOMES = Set.of("sent", "auth_denied");

    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;
    private final Set<String> activeWebSocketSessions = ConcurrentHashMap.newKeySet();

    @Autowired
    public TaskPilotMetrics(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this(
                meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new),
                observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP)
        );
    }

    public TaskPilotMetrics(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;

        Gauge.builder("taskpilot.websocket.sessions.active", activeWebSocketSessions, Set::size)
                .description("Currently connected authenticated WebSocket STOMP chat sessions")
                .register(this.meterRegistry);
    }

    public static TaskPilotMetrics noop() {
        return new TaskPilotMetrics(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    // -------------------------------------------------------------------------
    // AI Copilot Metrics
    // -------------------------------------------------------------------------

    public void recordAiCompletion(String providerOrModel, int tokensUsed, long durationMs) {
        String provider = normalizeAiProvider(providerOrModel);
        Counter.builder("taskpilot.ai.requests")
                .description("AI chat completion/fallback/error count by normalized provider and outcome")
                .tag("provider", provider)
                .tag("outcome", "success")
                .register(meterRegistry)
                .increment();

        if (tokensUsed > 0) {
            Counter.builder("taskpilot.ai.tokens")
                    .description("Estimated AI tokens consumed by normalized provider")
                    .tag("provider", provider)
                    .register(meterRegistry)
                    .increment(tokensUsed);
        }

        if (durationMs >= 0) {
            Timer.builder("taskpilot.ai.request.duration")
                    .description("End-to-end AI chat request duration by provider and outcome")
                    .tag("provider", provider)
                    .tag("outcome", "success")
                    .register(meterRegistry)
                    .record(Duration.ofMillis(durationMs));
        }
    }

    public void recordAiFallback(String providerOrModel) {
        String provider = normalizeAiProvider(providerOrModel);
        Counter.builder("taskpilot.ai.requests")
                .description("AI chat completion/fallback/error count by normalized provider and outcome")
                .tag("provider", provider)
                .tag("outcome", "fallback")
                .register(meterRegistry)
                .increment();
    }

    public void recordAiFailure(String providerOrModel, long durationMs) {
        String provider = normalizeAiProvider(providerOrModel);
        Counter.builder("taskpilot.ai.requests")
                .description("AI chat completion/fallback/error count by normalized provider and outcome")
                .tag("provider", provider)
                .tag("outcome", "error")
                .register(meterRegistry)
                .increment();

        if (durationMs >= 0) {
            Timer.builder("taskpilot.ai.request.duration")
                    .description("End-to-end AI chat request duration by provider and outcome")
                    .tag("provider", provider)
                    .tag("outcome", "error")
                    .register(meterRegistry)
                    .record(Duration.ofMillis(durationMs));
        }
    }

    public void recordAiAborted(String providerOrModel) {
        String provider = normalizeAiProvider(providerOrModel);
        Counter.builder("taskpilot.ai.requests")
                .description("AI chat completion/fallback/error count by normalized provider and outcome")
                .tag("provider", provider)
                .tag("outcome", "aborted")
                .register(meterRegistry)
                .increment();
    }

    public static String normalizeAiProvider(String rawProviderOrModel) {
        if (rawProviderOrModel == null || rawProviderOrModel.isBlank()) {
            return "other";
        }
        String lower = rawProviderOrModel.trim().toLowerCase(Locale.ROOT);
        if (VALID_AI_PROVIDERS.contains(lower)) {
            return lower;
        }
        if (lower.contains("openrouter") || lower.endsWith(":free")
                || lower.startsWith("nvidia/") || lower.startsWith("nex-agi/")
                || lower.startsWith("z-ai/") || lower.startsWith("poolside/")
                || lower.startsWith("google/")) {
            return "openrouter";
        }
        if (lower.contains("gemini") || lower.contains("gemma")) {
            return "gemini";
        }
        if (lower.contains("groq") || lower.startsWith("openai/gpt-oss")
                || lower.contains("llama") || lower.contains("mixtral")) {
            return "groq";
        }
        return "other";
    }

    // -------------------------------------------------------------------------
    // LiveKit Egress & Fallback Recording Metrics
    // -------------------------------------------------------------------------

    public void recordEgressRecording(String mode, String outcome) {
        String safeMode = sanitize(mode, VALID_EGRESS_MODES, "cloud");
        String safeOutcome = sanitize(outcome, VALID_EGRESS_OUTCOMES, "failed");
        Counter.builder("taskpilot.egress.recordings")
                .description("Meeting recording lifecycle outcomes across cloud Egress and client fallback")
                .tag("mode", safeMode)
                .tag("outcome", safeOutcome)
                .register(meterRegistry)
                .increment();
    }

    public void recordLiveKitWebhook(String rawEventName, String outcome) {
        String safeEvent = normalizeWebhookEvent(rawEventName);
        String safeOutcome = sanitize(outcome, VALID_WEBHOOK_OUTCOMES, "error");
        Counter.builder("taskpilot.livekit.webhooks")
                .description("LiveKit Cloud webhook events received and processed")
                .tag("event", safeEvent)
                .tag("outcome", safeOutcome)
                .register(meterRegistry)
                .increment();
    }

    public static String normalizeWebhookEvent(String rawEventName) {
        if (rawEventName == null || rawEventName.isBlank()) {
            return "other";
        }
        String lower = rawEventName.trim().toLowerCase(Locale.ROOT);
        if (VALID_WEBHOOK_EVENTS.contains(lower)) {
            return lower;
        }
        return "other";
    }

    // -------------------------------------------------------------------------
    // Hot B2 -> Cold Google Drive Archive Metrics
    // -------------------------------------------------------------------------

    public void recordArchiveTransfer(String outcome, long bytesTransferred, long durationMs) {
        String safeOutcome = sanitize(outcome, VALID_ARCHIVE_OUTCOMES, "failed");
        Counter.builder("taskpilot.archive.transfers")
                .description("Hot Backblaze B2 to cold Google Drive recording archive transfers")
                .tag("outcome", safeOutcome)
                .register(meterRegistry)
                .increment();

        if ("success".equals(safeOutcome) && bytesTransferred > 0) {
            Counter.builder("taskpilot.archive.bytes")
                    .description("Total recording bytes archived from Backblaze B2 to Google Drive")
                    .tag("outcome", "success")
                    .register(meterRegistry)
                    .increment(bytesTransferred);
        }

        if (durationMs >= 0) {
            String timerOutcome = "success".equals(safeOutcome) ? "success" : "failed";
            Timer.builder("taskpilot.archive.transfer.duration")
                    .description("Recording archive transfer duration from B2 to Google Drive")
                    .tag("outcome", timerOutcome)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(durationMs));
        }
    }

    // -------------------------------------------------------------------------
    // WebSocket STOMP Real-Time Metrics
    // -------------------------------------------------------------------------

    public void onWebSocketConnected(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            activeWebSocketSessions.add(sessionId);
        }
    }

    public void onWebSocketDisconnected(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            activeWebSocketSessions.remove(sessionId);
        }
    }

    public int getActiveWebSocketSessionCount() {
        return activeWebSocketSessions.size();
    }

    public void recordWebSocketMessage(String outcome) {
        String safeOutcome = sanitize(outcome, VALID_WS_MESSAGE_OUTCOMES, "sent");
        Counter.builder("taskpilot.websocket.messages")
                .description("WebSocket STOMP chat messages processed and auth denials")
                .tag("outcome", safeOutcome)
                .register(meterRegistry)
                .increment();
    }

    // -------------------------------------------------------------------------
    // Distributed Tracing (Observation) & Async Trace Context Propagation
    // -------------------------------------------------------------------------

    public ObservationRegistry getObservationRegistry() {
        return observationRegistry;
    }

    public <T> T observe(String observationName, String lowCardKey, String lowCardValue, Supplier<T> action) {
        Observation observation = Observation.createNotStarted(observationName, observationRegistry);
        if (lowCardKey != null && !lowCardKey.isBlank() && lowCardValue != null && !lowCardValue.isBlank()) {
            observation.lowCardinalityKeyValue(lowCardKey, lowCardValue);
        }
        return observation.observe(action);
    }

    public void observeRunnable(String observationName, String lowCardKey, String lowCardValue, Runnable action) {
        Observation observation = Observation.createNotStarted(observationName, observationRegistry);
        if (lowCardKey != null && !lowCardKey.isBlank() && lowCardValue != null && !lowCardValue.isBlank()) {
            observation.lowCardinalityKeyValue(lowCardKey, lowCardValue);
        }
        observation.observe(action);
    }

    /**
     * Captures active Micrometer/OpenTelemetry trace context & MDC from the calling thread
     * and wraps the given {@link Runnable} so virtual-thread / async tasks preserve the same traceId.
     */
    public Runnable wrapWithCurrentContext(Runnable task) {
        ContextSnapshot snapshot = CONTEXT_SNAPSHOT_FACTORY.captureAll();
        return snapshot.wrap(task);
    }

    private static String sanitize(String raw, Set<String> allowed, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }
}
