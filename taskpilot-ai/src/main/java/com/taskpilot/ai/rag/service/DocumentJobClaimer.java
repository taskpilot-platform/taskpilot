package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.ClaimedDocumentJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Handles atomic, concurrency-safe job claims from PostgreSQL using FOR UPDATE SKIP LOCKED
 * and processing_version fencing increments.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentJobClaimer {

    private final JdbcTemplate jdbcTemplate;

    private static final String CLAIM_JOB_SQL = """
            WITH candidate AS (
                SELECT id
                FROM documents
                WHERE status = 'QUEUED'
                   OR (
                        status = 'RETRY_WAIT'
                        AND next_attempt_at <= NOW()
                      )
                   OR (
                        status = 'PROCESSING'
                        AND lease_until < NOW()
                      )
                ORDER BY created_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE documents d
            SET status = 'PROCESSING',
                processing_version = d.processing_version + 1,
                lease_until = NOW() + (? * INTERVAL '1 minute'),
                next_attempt_at = NULL,
                updated_at = NOW()
            FROM candidate c
            WHERE d.id = c.id
            RETURNING d.id, d.processing_version;
            """;

    /**
     * Atomically claims the next eligible document for ingestion.
     *
     * @param leaseMinutes duration in minutes before this lease expires if not completed
     * @return ClaimedDocumentJob with documentId and newly incremented processingVersion, or empty if no work
     */
    @Transactional
    public Optional<ClaimedDocumentJob> claimNextJob(int leaseMinutes) {
        try {
            return jdbcTemplate.query(
                    CLAIM_JOB_SQL,
                    ps -> ps.setInt(1, leaseMinutes),
                    rs -> {
                        if (rs.next()) {
                            long id = rs.getLong("id");
                            int version = rs.getInt("processing_version");
                            log.info("Atomically claimed document ingestion job: documentId={}, processingVersion={}", id, version);
                            return Optional.of(new ClaimedDocumentJob(id, version));
                        }
                        return Optional.empty();
                    }
            );
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
