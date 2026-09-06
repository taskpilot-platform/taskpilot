package com.taskpilot.ai.rag.domain;

public enum DocumentStatus {
    UPLOADING,
    QUEUED,
    PROCESSING,
    RETRY_WAIT,
    READY,
    FAILED
}
