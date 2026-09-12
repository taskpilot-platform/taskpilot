package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import com.taskpilot.ai.rag.domain.ClaimedDocumentJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Scheduled poller driving durable document ingestion from PostgreSQL.
 * Claims one job atomically per poll tick without buffering in RAM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentJobPoller {

    private final DocumentJobClaimer documentJobClaimer;
    private final DocumentIngestionService documentIngestionService;
    private final RagEmbeddingProperties properties;

    @Scheduled(fixedDelayString = "${rag.embedding.poll-interval-ms:3000}")
    public void pollAndProcess() {
        try {
            Optional<ClaimedDocumentJob> jobOpt = documentJobClaimer.claimNextJob(properties.getLeaseDurationMinutes());
            if (jobOpt.isEmpty()) {
                return;
            }

            ClaimedDocumentJob job = jobOpt.get();
            log.info("Poller picked up document job id={}, processing_version={}",
                    job.documentId(), job.processingVersion());

            documentIngestionService.ingestDocument(job.documentId(), job.processingVersion());

        } catch (Exception e) {
            log.error("Unhandled error in DocumentJobPoller execution tick: {}", e.getMessage(), e);
        }
    }
}
