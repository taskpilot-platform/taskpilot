package com.taskpilot.ai.rag.domain;

/**
 * Represents an atomically claimed ingestion job holding the document ID and assigned fencing token.
 */
public record ClaimedDocumentJob(
        Long documentId,
        int processingVersion
) {
}
