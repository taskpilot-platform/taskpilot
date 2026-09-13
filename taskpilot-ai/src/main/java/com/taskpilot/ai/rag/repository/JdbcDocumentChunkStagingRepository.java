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
            INSERT INTO document_chunk_staging (document_id, chunk_index, content, embedding, created_at)
            VALUES (?, ?, ?, NULL, ?)
            ON CONFLICT (document_id, chunk_index) DO NOTHING
            """;

    private static final String UPDATE_EMBEDDINGS_FENCED_SQL = """
            UPDATE document_chunk_staging s
            SET embedding = CAST(? AS vector)
            FROM documents d
            WHERE s.document_id = d.id
              AND d.id = ?
              AND d.processing_version = ?
              AND d.status = 'PROCESSING'
              AND s.chunk_index = ?
              AND s.embedding IS NULL
            """;

    private static final String COPY_TO_PUBLISHED_SQL = """
            INSERT INTO document_chunks (document_id, project_id, chunk_index, content, embedding, created_at)
            SELECT s.document_id, ?, s.chunk_index, s.content, s.embedding, NOW()
            FROM document_chunk_staging s
            WHERE s.document_id = ?
            ORDER BY s.chunk_index ASC
            """;

    @Override
    public void stageInitialChunks(Long documentId, List<String> contents) {
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
                        ps.setInt(2, i);
                        ps.setString(3, contents.get(i));
                        ps.setTimestamp(4, now);
                    }

                    @Override
                    public int getBatchSize() {
                        return contents.size();
                    }
                }
        );
        log.info("Batch staged {} initial text chunks for docId={}", contents.size(), documentId);
    }

    @Override
    public boolean hasStagedChunks(Long documentId) {
        if (documentId == null) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM document_chunk_staging WHERE document_id = ? LIMIT 1",
                Integer.class,
                documentId
        );
        return count != null && count > 0;
    }

    @Override
    public List<StagedChunk> findPendingChunks(Long documentId, int limit) {
        if (documentId == null || limit <= 0) return Collections.emptyList();

        String sql = """
                SELECT id, document_id, chunk_index, content, created_at
                FROM document_chunk_staging
                WHERE document_id = ?
                  AND embedding IS NULL
                ORDER BY chunk_index ASC
                LIMIT ?
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setLong(1, documentId);
                    ps.setInt(2, limit);
                },
                (rs, rowNum) -> new StagedChunk(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        null,
                        rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : Instant.now()
                )
        );
    }

    @Override
    public List<StagedChunk> findAllStagedChunks(Long documentId) {
        if (documentId == null) return Collections.emptyList();

        String sql = """
                SELECT id, document_id, chunk_index, content, created_at,
                       (embedding IS NOT NULL) as has_vector
                FROM document_chunk_staging
                WHERE document_id = ?
                ORDER BY chunk_index ASC
                """;

        return jdbcTemplate.query(
                sql,
                ps -> ps.setLong(1, documentId),
                (rs, rowNum) -> new StagedChunk(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getBoolean("has_vector") ? new float[0] : null,
                        rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : Instant.now()
                )
        );
    }

    @Override
    public long countPendingChunks(Long documentId) {
        if (documentId == null) return 0L;
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM document_chunk_staging WHERE document_id = ? AND embedding IS NULL",
                Long.class,
                documentId
        );
        return count != null ? count : 0L;
    }

    @Override
    public int updateEmbeddingsFenced(Long documentId, int workerVersion, List<StagedChunk> chunks, List<float[]> embeddings) {
        if (chunks == null || chunks.isEmpty() || embeddings == null || embeddings.isEmpty()) {
            return 0;
        }
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(String.format(
                    "Chunks size (%d) does not match embeddings size (%d)", chunks.size(), embeddings.size()));
        }

        int[] updateCounts = jdbcTemplate.batchUpdate(
                UPDATE_EMBEDDINGS_FENCED_SQL,
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        StagedChunk chunk = chunks.get(i);
                        float[] vec = embeddings.get(i);
                        ps.setString(1, JdbcDocumentChunkRepository.toVectorString(vec));
                        ps.setLong(2, documentId);
                        ps.setInt(3, workerVersion);
                        ps.setInt(4, chunk.chunkIndex());
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                }
        );

        int totalAffected = 0;
        for (int count : updateCounts) {
            if (count > 0) {
                totalAffected += count;
            }
        }

        log.debug("Batch updated {} embeddings in staging with fencing (docId={}, version={})",
                totalAffected, documentId, workerVersion);
        return totalAffected;
    }

    @Override
    public int copyStagedToPublished(Long documentId, Long projectId) {
        if (documentId == null || projectId == null) {
            return 0;
        }

        int copied = jdbcTemplate.update(COPY_TO_PUBLISHED_SQL, projectId, documentId);
        log.info("Copied {} staged chunks to document_chunks for docId={}, projectId={}",
                copied, documentId, projectId);
        return copied;
    }

    @Override
    public void deleteStagedChunks(Long documentId) {
        if (documentId == null) return;
        int deleted = jdbcTemplate.update(
                "DELETE FROM document_chunk_staging WHERE document_id = ?",
                documentId
        );
        log.debug("Cleaned up {} staged chunks for docId={}", deleted, documentId);
    }

    @Override
    public void deleteAllByDocumentId(Long documentId) {
        deleteStagedChunks(documentId);
    }
}
