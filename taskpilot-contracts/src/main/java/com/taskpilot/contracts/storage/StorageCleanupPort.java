package com.taskpilot.contracts.storage;

import java.util.List;

/**
 * Contracts port for durable after-commit object cleanup and dead-letter retry.
 */
public interface StorageCleanupPort {

    void enqueueCleanup(StorageCleanupRequest request);

    void enqueueCleanupBatch(List<StorageCleanupRequest> requests);

    boolean retryDeadLetterJob(long jobId);

    List<StorageCleanupJobSummary> findJobsByProject(long projectId);
}
