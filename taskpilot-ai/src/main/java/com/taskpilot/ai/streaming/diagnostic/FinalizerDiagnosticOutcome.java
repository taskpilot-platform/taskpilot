package com.taskpilot.ai.streaming.diagnostic;

/**
 * Diagnostic outcome record capturing structured telemetry per attempt in TimeoutFallbackHandler.
 */
public record FinalizerDiagnosticOutcome(
        Long sessionId,
        String clientMessageId,
        String stage,
        int attempt,
        String provider,
        String modelName,
        String mode,
        long elapsedMs,
        Long firstTokenMs,
        FinalizerTerminalCause terminalCause,
        int partialChars,
        boolean watchdogWon,
        boolean finalizationExecuted,
        boolean lateCallbackIgnored,
        Throwable error
) {}
