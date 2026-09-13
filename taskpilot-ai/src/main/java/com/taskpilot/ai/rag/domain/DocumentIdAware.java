package com.taskpilot.ai.rag.domain;

public interface DocumentIdAware {
    Long documentId();

    default Long getDocumentId() {
        return documentId();
    }
}
