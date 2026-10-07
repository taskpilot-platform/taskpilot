package com.taskpilot.ai.streaming.diagnostic;

/**
 * Diagnostic outcome record capturing structured telemetry per attempt in IntermediateResponseStreamer.
 */
public record IntermediateDiagnosticOutcome(
        Long sessionId,
        String clientMessageId,
        String stage,
        int attempt,
        String provider,
        String modelName,
        String mode,
        long elapsedMs,
        Long firstTokenMs,
        IntermediateTerminalCause terminalCause,
        int partialChars,
        boolean watchdogWon,
        boolean continuationExecuted,
        boolean lateCallbackIgnored,
        Throwable error
) {}
