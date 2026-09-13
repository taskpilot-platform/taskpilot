package com.taskpilot.ai.rag.repository;

import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.ScoredChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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

    private static final String FIND_BY_PROJECT_NEAREST_SQL = """
            SELECT c.id, c.document_id, c.project_id, c.chunk_index, c.content,
                   d.original_filename AS document_name,
                   (1 - (c.embedding <=> CAST(? AS vector))) AS similarity_score
            FROM document_chunks c
            LEFT JOIN documents d ON c.document_id = d.id
            WHERE c.project_id = ?
            ORDER BY c.embedding <=> CAST(? AS vector) ASC
            LIMIT ?
            """;

    private static final String FIND_BY_DOCUMENT_NEAREST_SQL = """
            SELECT c.id, c.document_id, c.project_id, c.chunk_index, c.content,
                   d.original_filename AS document_name,
                   (1 - (c.embedding <=> CAST(? AS vector))) AS similarity_score
            FROM document_chunks c
            LEFT JOIN documents d ON c.document_id = d.id
            WHERE c.project_id = ?
              AND c.document_id = ?
            ORDER BY c.embedding <=> CAST(? AS vector) ASC
            LIMIT ?
            """;

    private static final RowMapper<ScoredChunk> SCORED_CHUNK_ROW_MAPPER = (rs, rowNum) -> new ScoredChunk(
            rs.getLong("id"),
            rs.getLong("document_id"),
            rs.getLong("project_id"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getDouble("similarity_score"),
            rs.getString("document_name")
    );

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
        return findByProjectAndNearest(projectId, queryVector, limit, minScore);
    }

    @Override
    public List<ScoredChunk> findByProjectAndNearest(Long projectId, float[] queryVector, int candidateLimit, double minScore) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector must not be null or empty");
        }

        int queryLimit = Math.max(1, candidateLimit);
        String vectorStr = toVectorString(queryVector);

        List<ScoredChunk> results = jdbcTemplate.query(
                FIND_BY_PROJECT_NEAREST_SQL,
                ps -> {
                    ps.setString(1, vectorStr);
                    ps.setLong(2, projectId);
                    ps.setString(3, vectorStr);
                    ps.setInt(4, queryLimit);
                },
                SCORED_CHUNK_ROW_MAPPER
        );

        // 1. Log RAW pgvector retrieval results before any filtering or mapping
        logRawRetrieval(results, projectId);

        // 2. Similarity threshold filter step
        List<ScoredChunk> filtered;
        if (minScore <= 0.0) {
            filtered = results;
        } else {
            filtered = results.stream()
                    .filter(chunk -> chunk.similarity() >= minScore)
                    .toList();
        }

        int removedCount = results.size() - filtered.size();
        log.info("[RAG FILTER]\nstage=similarity_threshold\nbefore={}\nafter={}\nremoved={}\nreason=similarity < minScore ({})",
                results.size(), filtered.size(), removedCount, minScore);

        for (ScoredChunk c : results) {
            if (c.content() != null && (c.content().contains("Data Security and Privacy") || c.content().contains("Managing sensitive medical records"))) {
                if (c.similarity() >= minScore) {
                    log.info("[RAG FILTER] OOAD target chunk (chunkId={}, docId={}) PASSED filter (similarity {:.4f} >= minScore {})",
                            c.chunkId(), c.documentId(), c.similarity(), minScore);
                } else {
                    log.info("[RAG FILTER] OOAD target chunk (chunkId={}, docId={}) REMOVED by filter (similarity {:.4f} < minScore {})",
                            c.chunkId(), c.documentId(), c.similarity(), minScore);
                }
            }
        }

        logRankings("[AFTER VECTOR SEARCH]", results);
        logRankings("[AFTER FILTER]", filtered);

        return filtered;
    }

    private void logRawRetrieval(List<ScoredChunk> results, Long projectId) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n[RAG RAW RETRIEVAL]");
        int topN = Math.min(10, results.size());
        for (int i = 0; i < topN; i++) {
            ScoredChunk c = results.get(i);
            double dist = 1.0 - c.similarity();
            String preview = sanitizePreview(c.content(), 250);
            sb.append(String.format("\n#%d\nchunkId=%d\ndocumentId=%d\nfileName=\"%s\"\nprojectId=%d\nchunkIndex=%d\nsimilarity=%.4f\ndistance=%.4f\ncontentPreview=\"%s\"\n",
                    i + 1, c.chunkId(), c.documentId(), c.documentName() != null ? c.documentName() : "Doc #" + c.documentId(),
                    c.projectId(), c.chunkIndex(), c.similarity(), dist, preview));
        }
        log.info("{}", sb.toString().trim());

        boolean targetFound = false;
        StringBuilder ooadSb = new StringBuilder();
        ooadSb.append("\n[OOAD TRACE]");
        for (int i = 0; i < results.size(); i++) {
            ScoredChunk c = results.get(i);
            boolean isOoadDoc = c.documentName() != null && c.documentName().contains("OOAD");
            boolean isTargetContent = c.content() != null && (c.content().contains("Data Security and Privacy") || c.content().contains("Managing sensitive medical records"));
            if (isOoadDoc || isTargetContent) {
                double dist = 1.0 - c.similarity();
                String preview = sanitizePreview(c.content(), 250);
                ooadSb.append(String.format("\nchunkId=%d\ndocumentId=%d\nchunkIndex=%d\nsimilarity=%.4f\ndistance=%.4f\nrawRank=%d\ncontentPreview=\"%s\"\n",
                        c.chunkId(), c.documentId(), c.chunkIndex(), c.similarity(), dist, i + 1, preview));
                if (isTargetContent) {
                    targetFound = true;
                }
            }
        }
        if (!targetFound) {
            ooadSb.append(String.format("\nTarget chunk containing 'Data Security and Privacy' / 'Managing sensitive medical records' NOT FOUND in raw pgvector results (total retrieved: %d)", results.size()));
        }
        log.info("{}", ooadSb.toString().trim());
    }

    private void logRankings(String label, List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append(label);
        int topN = Math.min(10, chunks.size());
        for (int i = 0; i < topN; i++) {
            ScoredChunk c = chunks.get(i);
            sb.append(String.format("\n#%d chunkId=%d, docId=%d, fileName=\"%s\", sim=%.4f",
                    i + 1, c.chunkId(), c.documentId(), c.documentName() != null ? c.documentName() : "Doc #" + c.documentId(), c.similarity()));
        }
        log.info("{}", sb.toString());
    }

    private String sanitizePreview(String content, int maxLen) {
        if (content == null) return "";
        String singleLine = content.replaceAll("[\\r\\n\\t]+", " ").trim();
        return singleLine.length() <= maxLen ? singleLine : singleLine.substring(0, maxLen) + "...";
    }

    @Override
    public List<ScoredChunk> findByDocumentAndNearest(Long projectId, Long documentId, float[] queryVector, int candidateLimit, double minScore) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector must not be null or empty");
        }

        int queryLimit = Math.max(1, candidateLimit);
        String vectorStr = toVectorString(queryVector);

        List<ScoredChunk> results = jdbcTemplate.query(
                FIND_BY_DOCUMENT_NEAREST_SQL,
                ps -> {
                    ps.setString(1, vectorStr);
                    ps.setLong(2, projectId);
                    ps.setLong(3, documentId);
                    ps.setString(4, vectorStr);
                    ps.setInt(5, queryLimit);
                },
                SCORED_CHUNK_ROW_MAPPER
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

    @Override
    public long countByDocumentId(Long documentId) {
        if (documentId == null) {
            return 0L;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE document_id = ?",
                Long.class,
                documentId
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
