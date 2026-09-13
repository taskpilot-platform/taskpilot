package com.taskpilot.ai.rag.service;

public interface DocumentIngestionService {

    /**
     * Executes the ingestion pipeline for a claimed document with optimistic fencing check.
     *
     * @param documentId document ID to process
     * @param claimedVersion the fencing token assigned during atomic claim
     */
    void ingestDocument(Long documentId, int claimedVersion);

    void deleteDocument(Long documentId);
}
