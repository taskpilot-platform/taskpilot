package com.taskpilot.infrastructure.storage.cleanup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Scheduled background worker that reconciles expired upload reservations
 * and processes committed storage cleanup jobs in bounded batches.
 */
@Slf4j
@Component
public class StorageCleanupWorker {

    private final StorageUploadOperationService uploadOperationService;
    private final StorageCleanupService cleanupService;
    private final int batchSize;
    private final String workerId;

    public StorageCleanupWorker(
            StorageUploadOperationService uploadOperationService,
            StorageCleanupService cleanupService,
            @Value("${taskpilot.storage.cleanup.batch-size:25}") int batchSize) {
        this.uploadOperationService = uploadOperationService;
        this.cleanupService = cleanupService;
        this.batchSize = Math.min(Math.max(batchSize, 1), 100);
        this.workerId = "storage-worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${taskpilot.storage.cleanup.poll-interval-ms:30000}")
    public void runCycle() {
        try {
            int reconciled = uploadOperationService.reconcileExpiredOperations(batchSize);
            int processed = cleanupService.processPendingBatch(batchSize, workerId);
            if (reconciled > 0 || processed > 0) {
                log.debug("Storage cleanup cycle finished: worker={}, reconciled={}, processed={}",
                        workerId, reconciled, processed);
            }
        } catch (Exception ex) {
            log.warn("Storage cleanup cycle encountered error (will retry next tick): {}", ex.getMessage());
        }
    }
}
