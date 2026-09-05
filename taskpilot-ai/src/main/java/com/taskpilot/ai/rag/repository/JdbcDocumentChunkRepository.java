package com.taskpilot.ai.rag.repository;

import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.ScoredChunk;
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
public class JdbcDocumentChunkRepository implements DocumentChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String INSERT_CHUNK_SQL = """
            INSERT INTO document_chunks (document_id, project_id, chunk_index, content, embedding, created_at)
            VALUES (?, ?, ?, ?, CAST(? AS vector), ?)
            """;

    private static final String FIND_NEAREST_SQL = """
            SELECT id, document_id, project_id, chunk_index, content,
                   (1 - (embedding <=> CAST(? AS vector))) AS similarity_score
            FROM document_chunks
            WHERE project_id = ?
            ORDER BY embedding <=> CAST(? AS vector)
            LIMIT ?
            """;

    private static final String FIND_BY_DOC_SQL = """
            SELECT id, document_id, project_id, chunk_index, content, created_at
            FROM document_chunks
            WHERE document_id = ?
            ORDER BY chunk_index ASC
            """;

    @Override
    public void saveAll(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                INSERT_CHUNK_SQL,
                chunks,
                chunks.size(),
                (ps, chunk) -> {
                    ps.setLong(1, chunk.documentId());
                    ps.setLong(2, chunk.projectId());
                    ps.setInt(3, chunk.chunkIndex());
                    ps.setString(4, chunk.content());
                    ps.setString(5, toVectorString(chunk.embedding()));
                    Instant createdAt = chunk.createdAt() != null ? chunk.createdAt() : Instant.now();
                    ps.setTimestamp(6, Timestamp.from(createdAt));
                }
        );

        log.info("Batch inserted {} document chunks into pgvector", chunks.size());
    }

    @Override
    public List<ScoredChunk> findNearestChunks(Long projectId, float[] queryVector, int limit, double minScore) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector must not be null or empty");
        }

        int queryLimit = Math.max(1, limit);
        String vectorStr = toVectorString(queryVector);

        List<ScoredChunk> results = jdbcTemplate.query(
                FIND_NEAREST_SQL,
                ps -> {
                    ps.setString(1, vectorStr);
                    ps.setLong(2, projectId);
                    ps.setString(3, vectorStr);
                    ps.setInt(4, queryLimit);
                },
                (rs, rowNum) -> new ScoredChunk(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getLong("project_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getDouble("similarity_score")
                )
        );

        if (minScore <= 0.0) {
            return results;
        }

        return results.stream()
                .filter(chunk -> chunk.similarity() >= minScore)
                .toList();
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        int rows = jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", documentId);
        log.info("Deleted {} chunks for documentId={}", rows, documentId);
    }

    @Override
    public void deleteByProjectId(Long projectId) {
        if (projectId == null) {
            return;
        }
        int rows = jdbcTemplate.update("DELETE FROM document_chunks WHERE project_id = ?", projectId);
        log.info("Deleted {} chunks for projectId={}", rows, projectId);
    }

    @Override
    public List<DocumentChunk> findByDocumentId(Long documentId) {
        if (documentId == null) {
            return Collections.emptyList();
        }

        return jdbcTemplate.query(
                FIND_BY_DOC_SQL,
                ps -> ps.setLong(1, documentId),
                (rs, rowNum) -> {
                    Timestamp ts = rs.getTimestamp("created_at");
                    Instant createdAt = ts != null ? ts.toInstant() : Instant.now();
                    return new DocumentChunk(
                            rs.getLong("id"),
                            rs.getLong("document_id"),
                            rs.getLong("project_id"),
                            rs.getInt("chunk_index"),
                            rs.getString("content"),
                            null, // Embedding vector omitted in standard projection for performance
                            createdAt
                    );
                }
        );
    }

    @Override
    public long countByProjectId(Long projectId) {
        if (projectId == null) {
            return 0L;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE project_id = ?",
                Long.class,
                projectId
        );
        return count != null ? count : 0L;
    }

    public static String toVectorString(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 9 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
