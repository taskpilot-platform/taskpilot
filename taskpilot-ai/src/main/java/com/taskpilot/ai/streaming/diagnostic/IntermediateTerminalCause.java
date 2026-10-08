package com.taskpilot.ai.streaming.diagnostic;

/**
 * Diagnostic terminal cause classification for the post-tool intermediate reasoning streamer.
 */
public enum IntermediateTerminalCause {
    INTERMEDIATE_COMPLETED_WITH_TEXT,
    INTERMEDIATE_COMPLETED_EMPTY,
    INTERMEDIATE_TIMEOUT_BEFORE_FIRST_TOKEN,
    INTERMEDIATE_TIMEOUT_AFTER_PARTIAL,
    INTERMEDIATE_MODEL_ERROR_BEFORE_FIRST_TOKEN,
    INTERMEDIATE_MODEL_ERROR_AFTER_PARTIAL,
    INTERMEDIATE_SYNC_START_FAILURE
}
