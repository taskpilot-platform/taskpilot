package com.taskpilot.ai.streaming.diagnostic;

/**
 * Diagnostic terminal cause classification for the post-tool finalizer text communicator.
 */
public enum FinalizerTerminalCause {
    FINALIZER_COMPLETED_WITH_TEXT,
    FINALIZER_COMPLETED_EMPTY,
    FINALIZER_TIMEOUT_BEFORE_FIRST_TOKEN,
    FINALIZER_MODEL_ERROR_BEFORE_FIRST_TOKEN,
    FINALIZER_MODEL_ERROR_AFTER_PARTIAL,
    FINALIZER_SYNC_START_FAILURE
}
