package com.taskpilot.contracts.project.port.out;

import java.util.Map;

/**
 * Cross-domain contract allowing projects permanent purge to remove project-scoped
 * AI, RAG, recommendation snapshot/outcome, and log records in the same DB transaction.
 */
public interface ProjectAiPurgePort {

    /**
     * Deletes all project-scoped AI/RAG/recommendation records in FK-safe order
     * within the caller's active database transaction.
     *
     * @return table-name to deleted-row-count summary for deletion audit
     */
    Map<String, Integer> purgeProjectAiData(long projectId);

    /**
     * Returns true if task-level AI recommendation outcomes reference this task
     * (via non-cascading FK), requiring tombstone retention rather than hard-delete.
     */
    default boolean hasTaskRecommendationOutcomes(long taskId) {
        return false;
    }
}
