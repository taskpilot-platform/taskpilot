package com.taskpilot.ai.rag.service;

public interface DocumentIngestionService {

    void ingestDocument(Long documentId);

    void ingestDocumentAsync(Long documentId);

    void deleteDocument(Long documentId);
}

