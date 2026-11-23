package com.taskpilot.ai.routing;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tracks model attempts, failed identities, Retry-After / quota scope metadata,
 * and shared turn deadline across all tool rounds and failover switches in one Copilot turn.
 */
public class TurnAttemptTracker {

    static final long MIN_CALL_WINDOW_MS = 2_000L;

    public enum CallKind {
        NORMAL_ROUND,
        KEY_ROTATION,
        FAILOVER,
        FAILOVER_FINALIZER
    }

    public enum TerminalReason {
        EXHAUSTED,
        MAX_ATTEMPTS,
        DEADLINE,
        NO_CANDIDATE
    }

    public sealed interface Decision permits RotateKey, SwitchTo, Terminal {}
    public record RotateKey() implements Decision {}
    public record SwitchTo(ModelCandidate candidate) implements Decision {}
    public record Terminal(TerminalReason reason) implements Decision {}

    public record AttemptRecord(
            String provider,
            String model,
            int keyAttempt,
            int toolRound,
            CallKind kind,
            Instant startedAt,
            String outcome) {

        AttemptRecord withOutcome(String newOutcome) {
            return new AttemptRecord(provider, model, keyAttempt, toolRound, kind, startedAt, newOutcome);
        }

        public String formatCompact() {
            return provider + ":" + model + "#r" + toolRound + " " + kind + " " + (outcome != null ? outcome : "pending");
        }
    }

    private final List<ModelCandidate> chain;
    private final int maxModelAttempts;
    private final int deadlineSeconds;
    private final Instant startTime;
    private final Instant deadline;
    private final InstantSource clock;

    private int cursor = -1;
    private int modelAttempts = 0;
    private final Set<String> failedIdentities = new HashSet<>();
    private final Map<String, Instant> notBeforeByIdentity = new HashMap<>();
    private final Map<String, Instant> notBeforeByScope = new HashMap<>();
    private final List<AttemptRecord> trace = new ArrayList<>();

    public TurnAttemptTracker(
            List<ModelCandidate> chain,
            int maxModelAttempts,
            int deadlineSeconds,
            InstantSource clock) {
        this.chain = chain == null ? List.of() : List.copyOf(chain);
        this.maxModelAttempts = Math.max(0, maxModelAttempts);
        this.deadlineSeconds = Math.max(1, deadlineSeconds);
        this.clock = clock != null ? clock : InstantSource.system();
        this.startTime = this.clock.instant();
        this.deadline = this.startTime.plusSeconds(this.deadlineSeconds);
    }

    public TurnAttemptTracker(List<ModelCandidate> chain, int maxModelAttempts, int deadlineSeconds) {
        this(chain, maxModelAttempts, deadlineSeconds, InstantSource.system());
    }

    public synchronized void activate(ModelCandidate first) {
        if (first == null) {
            return;
        }
        int idx = indexOfIdentity(first.identity());
        this.cursor = idx;
        this.modelAttempts = 1;
    }

    public synchronized void recordCall(ModelCandidate candidate, int keyAttempt, int toolRound, CallKind kind) {
        if (candidate == null) {
            return;
        }
        trace.add(new AttemptRecord(
                candidate.provider(),
                candidate.model(),
                keyAttempt,
                toolRound,
                kind,
                clock.instant(),
                "running"));
    }

    public synchronized void markLastOutcome(String outcome) {
        if (!trace.isEmpty()) {
            int lastIdx = trace.size() - 1;
            trace.set(lastIdx, trace.get(lastIdx).withOutcome(outcome));
        }
    }

    public synchronized boolean hasTimeForNextCall() {
        return remainingMillis() >= MIN_CALL_WINDOW_MS;
    }

    public synchronized long remainingMillis() {
        return Duration.between(clock.instant(), deadline).toMillis();
    }

    public synchronized long elapsedMillis() {
        return Math.max(0L, Duration.between(startTime, clock.instant()).toMillis());
    }

    public synchronized long watchdogTimeoutSeconds(long configuredSeconds) {
        long safeConfigured = Math.max(1L, configuredSeconds);
        long remainingMs = remainingMillis();
        long budgetSec = Math.max(1L, (remainingMs - 1_000L) / 1_000L);
        return Math.min(safeConfigured, budgetSec);
    }

    public synchronized Decision onFailure(
            ModelCandidate current,
            ModelFailureClassifier.Failure failure,
            boolean existingKeyRotationAvailable) {
        ModelFailureClassifier.Kind kind = failure != null ? failure.kind() : ModelFailureClassifier.Kind.TRANSIENT;
        markLastOutcome(kind.name());

        if (!hasTimeForNextCall()) {
            return new Terminal(TerminalReason.DEADLINE);
        }

        if ((kind == ModelFailureClassifier.Kind.TRANSIENT || kind == ModelFailureClassifier.Kind.FIRST_RESPONSE_TIMEOUT)
                && existingKeyRotationAvailable) {
            return new RotateKey();
        }

        if (current != null) {
            failedIdentities.add(current.identity());
            if (failure != null) {
                failure.notBefore().ifPresent(nb -> notBeforeByIdentity.put(current.identity(), nb));
                if (failure.scope() == ModelFailureClassifier.QuotaScope.PROJECT) {
                    String scope = scopeOf(current);
                    if (!scope.isEmpty()) {
                        Instant scopeUntil = failure.notBefore().orElse(deadline.plusSeconds(1));
                        notBeforeByScope.put(scope, scopeUntil);
                    }
                }
            }
        }

        return nextCandidate();
    }

    public synchronized Decision nextCandidate() {
        if (!hasTimeForNextCall()) {
            return new Terminal(TerminalReason.DEADLINE);
        }
        if (chain.isEmpty()) {
            return new Terminal(TerminalReason.NO_CANDIDATE);
        }
        Instant now = clock.instant();
        for (int i = cursor + 1; i < chain.size(); i++) {
            ModelCandidate candidate = chain.get(i);
            if (failedIdentities.contains(candidate.identity())) {
                continue;
            }
            String scope = scopeOf(candidate);
            if (!scope.isEmpty()) {
                Instant blockedUntil = notBeforeByScope.get(scope);
                if (blockedUntil != null && blockedUntil.isAfter(now)) {
                    failedIdentities.add(candidate.identity());
                    trace.add(new AttemptRecord(
                            candidate.provider(),
                            candidate.model(),
                            0,
                            -1,
                            CallKind.FAILOVER,
                            now,
                            "SCOPE_RATE_LIMITED"));
                    continue;
                }
            }
            if (maxModelAttempts > 0 && modelAttempts >= maxModelAttempts) {
                return new Terminal(TerminalReason.MAX_ATTEMPTS);
            }
            cursor = i;
            modelAttempts++;
            return new SwitchTo(candidate);
        }
        return new Terminal(TerminalReason.EXHAUSTED);
    }

    public synchronized int getModelAttempts() {
        return modelAttempts;
    }

    public synchronized int getMaxModelAttempts() {
        return maxModelAttempts;
    }

    public synchronized int getDeadlineSeconds() {
        return deadlineSeconds;
    }

    public synchronized Optional<Instant> getNotBefore(String identity) {
        return Optional.ofNullable(notBeforeByIdentity.get(identity));
    }

    public synchronized List<AttemptRecord> trace() {
        return List.copyOf(trace);
    }

    public synchronized String formatTrace() {
        return trace.stream()
                .map(AttemptRecord::formatCompact)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private int indexOfIdentity(String identity) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).identity().equals(identity)) {
                return i;
            }
        }
        return -1;
    }

    private static String scopeOf(ModelCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        if ("GEMINI".equalsIgnoreCase(candidate.provider())) {
            return "GEMINI";
        }
        return "";
    }
}
