package com.taskpilot.ai.routing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TurnAttemptTrackerTest {

    private static final ModelCandidate GROQ = new ModelCandidate("GROQ", "openai/gpt-oss-120b", true, ModelCandidate.Source.DEFAULT_CHAIN);
    private static final ModelCandidate GEMINI_A = new ModelCandidate("GEMINI", "gemini-3.5-flash", true, ModelCandidate.Source.DEFAULT_CHAIN);
    private static final ModelCandidate GEMINI_B = new ModelCandidate("GEMINI", "gemini-2.5-flash", true, ModelCandidate.Source.DEFAULT_CHAIN);
    private static final ModelCandidate OR_1 = new ModelCandidate("OPENROUTER", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", true, ModelCandidate.Source.DEFAULT_CHAIN);

    @Test
    void sharedDeadlineTracksToolRoundsAndFailoversWithoutRevisitingFailedModels() {
        AtomicReference<Instant> clockRef = new AtomicReference<>(Instant.parse("2026-11-23T00:00:00Z"));
        InstantSource fakeClock = clockRef::get;

        TurnAttemptTracker tracker = new TurnAttemptTracker(List.of(GROQ, GEMINI_A, GEMINI_B, OR_1), 4, 60, fakeClock);
        tracker.activate(GROQ);

        // Normal round 0 + round 1 on Groq do not increment modelAttempts
        tracker.recordCall(GROQ, 1, 0, TurnAttemptTracker.CallKind.NORMAL_ROUND);
        clockRef.set(clockRef.get().plusSeconds(15));
        tracker.recordCall(GROQ, 1, 1, TurnAttemptTracker.CallKind.NORMAL_ROUND);
        assertThat(tracker.getModelAttempts()).isEqualTo(1);
        assertThat(tracker.watchdogTimeoutSeconds(20)).isEqualTo(20);

        // Advance to t=45s -> remaining 15s -> watchdog capped to 14s
        clockRef.set(clockRef.get().plusSeconds(30));
        assertThat(tracker.watchdogTimeoutSeconds(20)).isEqualTo(14);

        // Groq fails 429 (model-scoped) -> immediately switch to GEMINI_A (even with Retry-After)
        var d1 = tracker.onFailure(GROQ,
                new ModelFailureClassifier.Failure(ModelFailureClassifier.Kind.RATE_LIMITED,
                        Optional.of(clockRef.get().plusSeconds(30)),
                        ModelFailureClassifier.QuotaScope.MODEL, "429"),
                true);
        assertThat(d1).isEqualTo(new TurnAttemptTracker.SwitchTo(GEMINI_A));
        assertThat(tracker.getModelAttempts()).isEqualTo(2);

        // Advance to t=59s -> remaining 1s (< 2s window) -> DEADLINE
        clockRef.set(clockRef.get().plusSeconds(14));
        assertThat(tracker.hasTimeForNextCall()).isFalse();
        var d2 = tracker.onFailure(GEMINI_A,
                new ModelFailureClassifier.Failure(ModelFailureClassifier.Kind.TRANSIENT, Optional.empty(),
                        ModelFailureClassifier.QuotaScope.MODEL, "503"),
                false);
        assertThat(d2).isEqualTo(new TurnAttemptTracker.Terminal(TurnAttemptTracker.TerminalReason.DEADLINE));
    }

    @Test
    void perModel429AdvancesToNextGeminiWhileProject429SkipsRemainingGemini() {
        Instant t0 = Instant.parse("2026-11-23T00:00:00Z");
        TurnAttemptTracker modelScopeTracker = new TurnAttemptTracker(List.of(GROQ, GEMINI_A, GEMINI_B, OR_1), 4, 60, () -> t0);
        modelScopeTracker.activate(GROQ);
        assertThat(modelScopeTracker.onFailure(GROQ,
                new ModelFailureClassifier.Failure(ModelFailureClassifier.Kind.TRANSIENT, Optional.empty(), ModelFailureClassifier.QuotaScope.MODEL, "503"), false))
                .isEqualTo(new TurnAttemptTracker.SwitchTo(GEMINI_A));
        assertThat(modelScopeTracker.onFailure(GEMINI_A,
                new ModelFailureClassifier.Failure(ModelFailureClassifier.Kind.RATE_LIMITED, Optional.of(t0.plusSeconds(35)), ModelFailureClassifier.QuotaScope.MODEL, "429"), false))
                .isEqualTo(new TurnAttemptTracker.SwitchTo(GEMINI_B));

        TurnAttemptTracker projectScopeTracker = new TurnAttemptTracker(List.of(GROQ, GEMINI_A, GEMINI_B, OR_1), 4, 60, () -> t0);
        projectScopeTracker.activate(GROQ);
        projectScopeTracker.onFailure(GROQ,
                new ModelFailureClassifier.Failure(ModelFailureClassifier.Kind.TRANSIENT, Optional.empty(), ModelFailureClassifier.QuotaScope.MODEL, "503"), false);
        assertThat(projectScopeTracker.onFailure(GEMINI_A,
                new ModelFailureClassifier.Failure(ModelFailureClassifier.Kind.RATE_LIMITED, Optional.of(t0.plusSeconds(30)), ModelFailureClassifier.QuotaScope.PROJECT, "429 PerProject"), false))
                .isEqualTo(new TurnAttemptTracker.SwitchTo(OR_1));
        assertThat(projectScopeTracker.formatTrace()).contains("SCOPE_RATE_LIMITED");
    }
}
