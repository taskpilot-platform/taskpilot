package com.taskpilot.ai.rag.repository;

import com.taskpilot.ai.rag.domain.StagedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcDocumentChunkStagingRepository implements DocumentChunkStagingRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String STAGE_CHUNK_SQL = """
            INSERT INTO document_chunk_staging (document_id, processing_version, chunk_index, content, embedding, created_at)
            VALUES (?, ?, ?, ?, NULL, ?)
            ON CONFLICT (document_id, processing_version, chunk_index) DO NOTHING
            """;

    private static final String UPDATE_EMBEDDING_SQL = """
            UPDATE document_chunk_staging
            SET embedding = CAST(? AS vector)
            WHERE id = ?
            """;

    private static final String COPY_TO_PUBLISHED_SQL = """
            INSERT INTO document_chunks (document_id, project_id, chunk_index, content, embedding, created_at)
            SELECT s.document_id, ?, s.chunk_index, s.content, s.embedding, NOW()
            FROM document_chunk_staging s
            WHERE s.document_id = ? AND s.processing_version = ?
            ORDER BY s.chunk_index ASC
            """;

    @Override
    public void stageInitialChunks(Long documentId, int processingVersion, List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return;
        }

        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.batchUpdate(
                STAGE_CHUNK_SQL,
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        ps.setLong(1, documentId);
                        ps.setInt(2, processingVersion);
                        ps.setInt(3, i);
                        ps.setString(4, contents.get(i));
                        ps.setTimestamp(5, now);
                    }

                    @Override
                    public int getBatchSize() {
                        return contents.size();
                    }
                }
        );
        log.info("Batch staged {} initial text chunks for docId={}, version={}", contents.size(), documentId, processingVersion);
    }

    @Override
    public boolean hasStagedChunks(Long documentId, int processingVersion) {
        if (documentId == null) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM document_chunk_staging WHERE document_id = ? AND processing_version = ? LIMIT 1",
                Integer.class,
                documentId, processingVersion
        );
        return count != null && count > 0;
    }

    @Override
    public List<StagedChunk> findPendingChunks(Long documentId, int processingVersion) {
        if (documentId == null) return Collections.emptyList();

        String sql = """
                SELECT id, document_id, processing_version, chunk_index, content, created_at
                FROM document_chunk_staging
                WHERE document_id = ?
                  AND processing_version = ?
                  AND embedding IS NULL
                ORDER BY chunk_index ASC
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setLong(1, documentId);
                    ps.setInt(2, processingVersion);
                },
                (rs, rowNum) -> new StagedChunk(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getInt("processing_version"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        null,
                        rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : Instant.now()
                )
        );
    }

    @Override
    public List<StagedChunk> findAllStagedChunks(Long documentId, int processingVersion) {
        if (documentId == null) return Collections.emptyList();

        String sql = """
                SELECT id, document_id, processing_version, chunk_index, content, created_at,
                       (embedding IS NOT NULL) as has_vector
                FROM document_chunk_staging
                WHERE document_id = ?
                  AND processing_version = ?
                ORDER BY chunk_index ASC
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setLong(1, documentId);
                    ps.setInt(2, processingVersion);
                },
                (rs, rowNum) -> new StagedChunk(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getInt("processing_version"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getBoolean("has_vector") ? new float[0] : null,
                        rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : Instant.now()
                )
        );
    }

    @Override
    public long countPendingChunks(Long documentId, int processingVersion) {
        if (documentId == null) return 0L;
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM document_chunk_staging WHERE document_id = ? AND processing_version = ? AND embedding IS NULL",
                Long.class,
                documentId, processingVersion
        );
        return count != null ? count : 0L;
    }

    @Override
    public void updateEmbeddings(List<StagedChunk> chunks, List<float[]> embeddings) {
        if (chunks == null || chunks.isEmpty() || embeddings == null || embeddings.isEmpty()) {
            return;
        }
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(String.format(
                    "Chunks size (%d) does not match embeddings size (%d)", chunks.size(), embeddings.size()));
        }

        jdbcTemplate.batchUpdate(
                UPDATE_EMBEDDING_SQL,
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        StagedChunk chunk = chunks.get(i);
                        float[] vec = embeddings.get(i);
                        ps.setString(1, JdbcDocumentChunkRepository.toVectorString(vec));
                        ps.setLong(2, chunk.id());
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                }
        );

        log.debug("Batch updated {} embeddings in document_chunk_staging", chunks.size());
    }

    @Override
    public int copyStagedToPublished(Long documentId, int processingVersion, Long projectId) {
        if (documentId == null || projectId == null) {
            return 0;
        }

        int copied = jdbcTemplate.update(COPY_TO_PUBLISHED_SQL, projectId, documentId, processingVersion);
        log.info("Copied {} staged chunks to document_chunks for docId={}, version={}, projectId={}",
                copied, documentId, processingVersion, projectId);
        return copied;
    }

    @Override
    public void deleteStagedChunks(Long documentId, int processingVersion) {
        if (documentId == null) return;
        int deleted = jdbcTemplate.update(
                "DELETE FROM document_chunk_staging WHERE document_id = ? AND processing_version = ?",
                documentId, processingVersion
        );
        log.debug("Cleaned up {} staged chunks for docId={} version={}", deleted, documentId, processingVersion);
    }

    @Override
    public int adoptOlderStagedChunks(Long documentId, int currentVersion) {
        if (documentId == null) return 0;
        int updated = jdbcTemplate.update(
                "UPDATE document_chunk_staging SET processing_version = ? WHERE document_id = ? AND processing_version < ?",
                currentVersion, documentId, currentVersion
        );
        if (updated > 0) {
            log.info("Adopted {} existing staged chunks for docId={} to version={}", updated, documentId, currentVersion);
        }
        return updated;
    }

    @Override
    public void deleteOlderStagedChunks(Long documentId, int currentVersion) {
        if (documentId == null) return;
        int deleted = jdbcTemplate.update(
                "DELETE FROM document_chunk_staging WHERE document_id = ? AND processing_version < ?",
                documentId, currentVersion
        );
        if (deleted > 0) {
            log.info("Cleaned up {} older staged chunks for docId={} prior to version={}", deleted, documentId, currentVersion);
        }
    }

    @Override
    public void deleteAllByDocumentId(Long documentId) {
        if (documentId == null) return;
        int deleted = jdbcTemplate.update("DELETE FROM document_chunk_staging WHERE document_id = ?", documentId);
        if (deleted > 0) {
            log.debug("Cleaned up {} staged chunks for docId={}", deleted, documentId);
        }
    }
}
