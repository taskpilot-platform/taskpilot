package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.config.RagEmbeddingProperties;
import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.domain.StagedChunk;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentChunkStagingRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.infrastructure.storage.StorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end deterministic regression test verifying large-document ingestion correctness:
 * - Extraction beyond 100,000 characters without truncation
 * - Deterministic chunking preserving first and last page content
 * - Complete staging of all chunks (chunk_index 0 to N-1)
 * - Resumable embedding only processing chunks where embedding IS NULL
 * - Fenced atomic publication into published document_chunks
 * - RAG vector retrieval successfully locating Beginning, Middle, and End-of-document content.
 */
class LargeDocumentIngestionAndRetrievalTest {

    private static final int EMBEDDING_DIM = 768;
    private static final Long PROJECT_ID = 100L;
    private static final Long USER_ID = 42L;
    private static final Long DOC_ID = 999L;

    private TikaDocumentTextExtractor textExtractor;
    private LangChain4jDocumentChunker chunker;
    private RagEmbeddingProperties properties;

    // In-memory stateful test doubles
    private InMemoryStagingRepository stagingRepository;
    private InMemoryPublishedRepository publishedRepository;
    private InMemoryDocumentRepository documentRepository;
    private StorageService storageService;
    private EmbeddingGateway embeddingGateway;
    private ProjectMemberPort projectMemberPort;
    private JdbcTemplate jdbcTemplate;

    private DocumentIngestionServiceImpl ingestionService;
    private ProjectKnowledgeServiceImpl knowledgeService;

    @BeforeEach
    void setUp() {
        textExtractor = new TikaDocumentTextExtractor();
        chunker = new LangChain4jDocumentChunker();
        properties = new RagEmbeddingProperties(12, 2, 50, 10, 5, 3000L);

        stagingRepository = new InMemoryStagingRepository();
        publishedRepository = new InMemoryPublishedRepository();
        documentRepository = new InMemoryDocumentRepository();
        storageService = mock(StorageService.class);
        embeddingGateway = mock(EmbeddingGateway.class);
        projectMemberPort = mock(ProjectMemberPort.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        when(projectMemberPort.isProjectMember(PROJECT_ID, USER_ID)).thenReturn(true);

        ingestionService = new DocumentIngestionServiceImpl(
                documentRepository,
                publishedRepository,
                stagingRepository,
                storageService,
                textExtractor,
                chunker,
                embeddingGateway,
                properties,
                jdbcTemplate,
                null // direct transaction simulation
        );

        knowledgeService = new ProjectKnowledgeServiceImpl(
                projectMemberPort,
                publishedRepository,
                embeddingGateway
        );
    }

    @Test
    @DisplayName("Verify end-to-end large document (>100k chars) pipeline: extraction -> chunking -> staging -> resumable embedding -> publication -> retrieval")
    void testLargeDocumentPipelineEndToEnd() throws Exception {
        // 1. Generate synthetic large PDF in memory (>100k chars, 120 pages)
        byte[] pdfBytes = generateSyntheticLargePdf(120);
        assertThat(pdfBytes.length).isGreaterThan(10_000);

        when(storageService.downloadFile(eq("documents"), eq("storage/large_architecture.pdf")))
                .thenAnswer(inv -> new ByteArrayInputStream(pdfBytes));

        // Initial document state in DB
        DocumentEntity initialDoc = DocumentEntity.builder()
                .id(DOC_ID)
                .projectId(PROJECT_ID)
                .storageKey("storage/large_architecture.pdf")
                .originalFilename("large_architecture.pdf")
                .contentType("application/pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(1)
                .retryCount(0)
                .build();
        documentRepository.save(initialDoc);

        // Mock JdbcTemplate to reflect atomic query behavior
        setupMockJdbcTemplate(DOC_ID, 1);

        // Deterministic Embedding Gateway: generates normalized 768-dim vector based on text markers
        when(embeddingGateway.embedForIngestion(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(this::deterministicVectorForText).toList();
        });

        when(embeddingGateway.embedForSearch(anyString())).thenAnswer(inv -> {
            String query = inv.getArgument(0);
            return deterministicVectorForText(query);
        });

        // 2. Perform complete ingestion
        ingestionService.ingestDocument(DOC_ID, 1);

        // 3. Verify extraction completeness
        assertThat(documentRepository.findById(DOC_ID)).isPresent();
        DocumentEntity finishedDoc = documentRepository.findById(DOC_ID).get();
        assertThat(finishedDoc.getStatus()).isEqualTo(DocumentStatus.READY);

        // 4. Verify chunking and publication completeness
        List<DocumentChunk> published = publishedRepository.findByDocumentId(DOC_ID);
        assertThat(published)
                .as("Large document chunk count must exceed 100k character boundary (> 150 chunks)")
                .hasSizeGreaterThan(150);

        int totalChunks = published.size();
        assertThat(published.get(0).chunkIndex()).isEqualTo(0);
        assertThat(published.get(totalChunks - 1).chunkIndex()).isEqualTo(totalChunks - 1);

        // Verify continuous chunk indices without gaps
        for (int i = 0; i < totalChunks; i++) {
            assertThat(published.get(i).chunkIndex()).isEqualTo(i);
        }

        // 5. Verify Beginning, Milestone, Middle, and End-of-document content survived in published chunks
        DocumentChunk firstChunk = published.get(0);
        assertThat(firstChunk.content()).contains("LARGE_DOC_PAGE_001");

        boolean containsPage46 = published.stream().anyMatch(c -> c.content().contains("LARGE_DOC_PAGE_046"));
        assertThat(containsPage46).as("Chunk around page 46 must exist in published index").isTrue();

        boolean containsPage400 = published.stream().anyMatch(c -> c.content().contains("LARGE_DOC_PAGE_400"));
        assertThat(containsPage400).as("Middle chunk (page 60/400) must exist in published index").isTrue();

        DocumentChunk lastChunk = published.get(totalChunks - 1);
        assertThat(lastChunk.content())
                .as("Final chunk must contain the end-of-document marker")
                .contains("LARGE_DOC_PAGE_800");

        // 6. Verify vector retrieval for Beginning, Middle, and End markers
        // Query Beginning
        List<ScoredChunk> beginningResults = knowledgeService.searchKnowledge(
                PROJECT_ID, USER_ID, "LARGE_DOC_PAGE_001", 3, 0.40);
        assertThat(beginningResults).isNotEmpty();
        assertThat(beginningResults.get(0).content()).contains("LARGE_DOC_PAGE_001");
        assertThat(beginningResults.get(0).similarity()).isGreaterThanOrEqualTo(0.95);

        // Query Middle
        List<ScoredChunk> middleResults = knowledgeService.searchKnowledge(
                PROJECT_ID, USER_ID, "LARGE_DOC_PAGE_400", 3, 0.40);
        assertThat(middleResults).isNotEmpty();
        assertThat(middleResults.get(0).content()).contains("LARGE_DOC_PAGE_400");
        assertThat(middleResults.get(0).similarity()).isGreaterThanOrEqualTo(0.95);

        // Query End-of-Document (The exact point where the 100k bug previously caused failure)
        List<ScoredChunk> endResults = knowledgeService.searchKnowledge(
                PROJECT_ID, USER_ID, "LARGE_DOC_PAGE_800", 3, 0.40);
        assertThat(endResults).isNotEmpty();
        assertThat(endResults.get(0).content()).contains("LARGE_DOC_PAGE_800");
        assertThat(endResults.get(0).similarity()).isGreaterThanOrEqualTo(0.95);

        System.out.println("Verified large document ingestion: " + totalChunks + " chunks published, all markers retrieved.");
    }

    @Test
    @DisplayName("Verify resumable embedding: partial failure resumes only from embedding IS NULL without re-extracting")
    void testResumableIngestionFromStagingWithoutReExtraction() throws Exception {
        byte[] pdfBytes = generateSyntheticLargePdf(60);
        when(storageService.downloadFile(eq("documents"), eq("storage/resumable_doc.pdf")))
                .thenAnswer(inv -> new ByteArrayInputStream(pdfBytes));

        DocumentEntity doc = DocumentEntity.builder()
                .id(DOC_ID)
                .projectId(PROJECT_ID)
                .storageKey("storage/resumable_doc.pdf")
                .originalFilename("resumable_doc.pdf")
                .contentType("application/pdf")
                .status(DocumentStatus.PROCESSING)
                .processingVersion(1)
                .retryCount(0)
                .build();
        documentRepository.save(doc);

        setupMockJdbcTemplate(DOC_ID, 1);

        // Phase 1: Simulate transient failure on 2nd embedding batch
        AtomicInteger batchCallCount = new AtomicInteger(0);
        when(embeddingGateway.embedForIngestion(anyList())).thenAnswer(inv -> {
            int count = batchCallCount.incrementAndGet();
            if (count == 2) {
                throw new RuntimeException("429 RESOURCE_EXHAUSTED - Quota exceeded simulation");
            }
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(this::deterministicVectorForText).toList();
        });

        // Run first ingestion attempt (fails on batch 2)
        ingestionService.ingestDocument(DOC_ID, 1);

        // Verify partial staging state
        long totalStaged = stagingRepository.findAllStagedChunks(DOC_ID).size();
        long pendingRemaining = stagingRepository.countPendingChunks(DOC_ID);
        long alreadyEmbedded = totalStaged - pendingRemaining;

        assertThat(totalStaged).isGreaterThan(60);
        assertThat(alreadyEmbedded).isEqualTo(50); // First batch of 50 succeeded
        assertThat(pendingRemaining).isGreaterThan(0); // Remainder still pending

        // Verify document is NOT marked ready
        assertThat(publishedRepository.findByDocumentId(DOC_ID)).isEmpty();

        // Phase 2: Resume attempt
        // Simulate poller / claimer reclaiming document into PROCESSING with version 2
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setProcessingVersion(2);
        documentRepository.save(doc);
        setupMockJdbcTemplate(DOC_ID, 2);

        // Reset gateway mock to succeed
        when(embeddingGateway.embedForIngestion(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(this::deterministicVectorForText).toList();
        });

        // Second ingestion run (resumes under version 2)
        ingestionService.ingestDocument(DOC_ID, 2);

        // Verify all chunks are now embedded and published
        assertThat(stagingRepository.countPendingChunks(DOC_ID)).isEqualTo(0);
        List<DocumentChunk> published = publishedRepository.findByDocumentId(DOC_ID);
        assertThat(published).hasSize((int) totalStaged);

        // Storage downloadFile should NOT have been called again on resume!
        verify(storageService, times(1)).downloadFile(anyString(), anyString());
    }

    private void setupMockJdbcTemplate(Long docId, int claimedVersion) {
        when(jdbcTemplate.update(contains("lease_until = NOW() + INTERVAL '3 minutes'"), eq(docId), eq(claimedVersion)))
                .thenReturn(1);

        when(jdbcTemplate.update(contains("retry_count = 0"), eq(docId), eq(claimedVersion)))
                .thenReturn(1);

        when(jdbcTemplate.query(
                contains("FOR UPDATE"),
                any(PreparedStatementSetter.class),
                any(RowMapper.class)
        )).thenAnswer(inv -> List.of(docId));

        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM document_chunk_staging"),
                eq(Long.class),
                eq(docId)
        )).thenAnswer(inv -> stagingRepository.countPendingChunks(docId));

        when(jdbcTemplate.update(contains("status = 'READY'"), eq(docId), eq(claimedVersion)))
                .thenAnswer(inv -> {
                    documentRepository.findById(docId).ifPresent(d -> d.setStatus(DocumentStatus.READY));
                    return 1;
                });

        when(jdbcTemplate.query(
                contains("SELECT retry_count FROM documents"),
                any(PreparedStatementSetter.class),
                any(RowMapper.class)
        )).thenAnswer(inv -> List.of(0));

        when(jdbcTemplate.update(contains("RETRY_WAIT"), any(), any(), any(), eq(docId), eq(claimedVersion)))
                .thenAnswer(inv -> {
                    documentRepository.findById(docId).ifPresent(d -> d.setStatus(DocumentStatus.RETRY_WAIT));
                    return 1;
                });
    }

    private float[] deterministicVectorForText(String text) {
        float[] vec = new float[EMBEDDING_DIM];
        Arrays.fill(vec, 0.001f);

        if (text.contains("LARGE_DOC_PAGE_001")) {
            vec[1] = 1.0f;
        } else if (text.contains("LARGE_DOC_PAGE_046")) {
            vec[46] = 1.0f;
        } else if (text.contains("LARGE_DOC_PAGE_400")) {
            vec[400] = 1.0f;
        } else if (text.contains("LARGE_DOC_PAGE_800")) {
            vec[700] = 1.0f;
        } else {
            // General hash projection
            int idx = Math.abs(text.hashCode()) % EMBEDDING_DIM;
            vec[idx] = 1.0f;
        }

        // Normalize vector to unit length
        float norm = 0.0f;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < vec.length; i++) vec[i] /= norm;

        return vec;
    }

    private byte[] generateSyntheticLargePdf(int pageCount) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int pageNum = 1; pageNum <= pageCount; pageNum++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 10);
                    cs.newLineAtOffset(50, 700);

                    if (pageNum == 1) {
                        cs.showText("LARGE_DOC_PAGE_001: Beginning of TaskPilot Master Architecture Specification.");
                    } else if (pageNum == 46) {
                        cs.showText("LARGE_DOC_PAGE_046: Milestone crossing the 100,000 character limit.");
                    } else if (pageNum == 60) {
                        cs.showText("LARGE_DOC_PAGE_400: Midpoint section detailing RAG and intelligent agent allocation.");
                    } else {
                        cs.showText("Page " + pageNum + " content: TaskPilot provides intelligent multi-agent task distribution.");
                    }
                    cs.newLineAtOffset(0, -20);
                    for (int line = 1; line <= 15; line++) {
                        cs.showText("Paragraph line " + line + ": Robust distributed task management, resilient resumable staging, and pgvector HNSW indexing in TaskPilot platform.");
                        cs.newLineAtOffset(0, -15);
                    }
                    if (pageNum == pageCount) {
                        cs.newLineAtOffset(0, -15);
                        cs.showText("LARGE_DOC_PAGE_800: Final chapter, concluding remarks, and system sign-off.");
                    }
                    cs.endText();
                }
            }
            doc.save(baos);
        }
        return baos.toByteArray();
    }

    // --- State-Tracking In-Memory Test Doubles ---

    private static class InMemoryDocumentRepository implements DocumentRepository {
        private final Map<Long, DocumentEntity> store = new ConcurrentHashMap<>();

        @Override public Optional<DocumentEntity> findById(Long id) { return Optional.ofNullable(store.get(id)); }
        @Override public <S extends DocumentEntity> S save(S entity) { store.put(entity.getId(), entity); return entity; }
        @Override public void delete(DocumentEntity entity) { store.remove(entity.getId()); }
        @Override public boolean existsById(Long id) { return store.containsKey(id); }
        @Override public List<DocumentEntity> findByProjectId(Long projectId) { return store.values().stream().filter(d -> Objects.equals(d.getProjectId(), projectId)).toList(); }
        @Override public List<DocumentEntity> findByProjectIdAndStatus(Long projectId, DocumentStatus status) {
            return store.values().stream().filter(d -> Objects.equals(d.getProjectId(), projectId) && d.getStatus() == status).toList();
        }
        @Override public Optional<DocumentEntity> findByIdAndProjectId(Long id, Long projectId) { return Optional.ofNullable(store.get(id)).filter(d -> Objects.equals(d.getProjectId(), projectId)); }
        @Override public List<DocumentEntity> findByStatusAndUpdatedAtBefore(DocumentStatus status, java.time.Instant before) { return Collections.emptyList(); }
        @Override public void deleteByProjectId(Long projectId) { store.values().removeIf(d -> Objects.equals(d.getProjectId(), projectId)); }
        @Override public List<DocumentEntity> findAll() { return new ArrayList<>(store.values()); }
        @Override public List<DocumentEntity> findAllById(Iterable<Long> ids) { return Collections.emptyList(); }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(Long id) { store.remove(id); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void deleteAll(Iterable<? extends DocumentEntity> entities) {}
        @Override public void deleteAll() { store.clear(); }
        @Override public <S extends DocumentEntity> List<S> saveAll(Iterable<S> entities) { return Collections.emptyList(); }
        @Override public void flush() {}
        @Override public <S extends DocumentEntity> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends DocumentEntity> List<S> saveAllAndFlush(Iterable<S> entities) { return Collections.emptyList(); }
        @Override public void deleteAllInBatch(Iterable<DocumentEntity> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public DocumentEntity getOne(Long id) { return store.get(id); }
        @Override public DocumentEntity getById(Long id) { return store.get(id); }
        @Override public DocumentEntity getReferenceById(Long id) { return store.get(id); }
        @Override public <S extends DocumentEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends DocumentEntity> List<S> findAll(org.springframework.data.domain.Example<S> example) { return Collections.emptyList(); }
        @Override public <S extends DocumentEntity> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return Collections.emptyList(); }
        @Override public <S extends DocumentEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends DocumentEntity> long count(org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends DocumentEntity> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends DocumentEntity, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public List<DocumentEntity> findAll(org.springframework.data.domain.Sort sort) { return Collections.emptyList(); }
        @Override public org.springframework.data.domain.Page<DocumentEntity> findAll(org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
    }

    private static class InMemoryPublishedRepository implements DocumentChunkRepository {
        private final List<DocumentChunk> chunks = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong idGen = new AtomicLong(1);

        @Override
        public void saveAll(List<DocumentChunk> newChunks) {
            chunks.addAll(newChunks);
        }

        @Override
        public List<ScoredChunk> findNearestChunks(Long projectId, float[] queryVector, int limit, double minScore) {
            return chunks.stream()
                    .filter(c -> Objects.equals(c.projectId(), projectId))
                    .map(c -> {
                        double sim = cosineSimilarity(c.embedding(), queryVector);
                        return new ScoredChunk(c.id(), c.documentId(), c.projectId(), c.chunkIndex(), c.content(), sim);
                    })
                    .filter(sc -> sc.similarity() >= minScore)
                    .sorted((a, b) -> Double.compare(b.similarity(), a.similarity()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<DocumentChunk> findByDocumentId(Long documentId) {
            return chunks.stream()
                    .filter(c -> Objects.equals(c.documentId(), documentId))
                    .sorted(Comparator.comparingInt(DocumentChunk::chunkIndex))
                    .toList();
        }

        @Override
        public void deleteByDocumentId(Long documentId) {
            chunks.removeIf(c -> Objects.equals(c.documentId(), documentId));
        }

        @Override
        public void deleteByProjectId(Long projectId) {
            chunks.removeIf(c -> Objects.equals(c.projectId(), projectId));
        }

        @Override
        public long countByProjectId(Long projectId) {
            return chunks.stream().filter(c -> Objects.equals(c.projectId(), projectId)).count();
        }

        @Override
        public long countByDocumentId(Long documentId) {
            return chunks.stream().filter(c -> Objects.equals(c.documentId(), documentId)).count();
        }

        private double cosineSimilarity(float[] v1, float[] v2) {
            if (v1 == null || v2 == null) return 0.0;
            double dot = 0.0, normA = 0.0, normB = 0.0;
            for (int i = 0; i < v1.length; i++) {
                dot += v1[i] * v2[i];
                normA += v1[i] * v1[i];
                normB += v2[i] * v2[i];
            }
            if (normA == 0 || normB == 0) return 0.0;
            return dot / (Math.sqrt(normA) * Math.sqrt(normB));
        }
    }

    private class InMemoryStagingRepository implements DocumentChunkStagingRepository {
        private final Map<Long, StagedChunk> stageStore = new ConcurrentHashMap<>();
        private final AtomicLong idSeq = new AtomicLong(1);

        @Override
        public void stageInitialChunks(Long documentId, List<String> contents) {
            for (int i = 0; i < contents.size(); i++) {
                long id = idSeq.getAndIncrement();
                stageStore.put(id, new StagedChunk(id, documentId, i, contents.get(i), null, Instant.now()));
            }
        }

        @Override
        public boolean hasStagedChunks(Long documentId) {
            return stageStore.values().stream()
                    .anyMatch(c -> Objects.equals(c.documentId(), documentId));
        }

        @Override
        public List<StagedChunk> findPendingChunks(Long documentId, int limit) {
            return stageStore.values().stream()
                    .filter(c -> Objects.equals(c.documentId(), documentId)
                            && c.embedding() == null)
                    .sorted(Comparator.comparingInt(StagedChunk::chunkIndex))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<StagedChunk> findAllStagedChunks(Long documentId) {
            return stageStore.values().stream()
                    .filter(c -> Objects.equals(c.documentId(), documentId))
                    .sorted(Comparator.comparingInt(StagedChunk::chunkIndex))
                    .toList();
        }

        @Override
        public long countPendingChunks(Long documentId) {
            return stageStore.values().stream()
                    .filter(c -> Objects.equals(c.documentId(), documentId)
                            && c.embedding() == null)
                    .count();
        }

        @Override
        public int updateEmbeddingsFenced(Long documentId, int workerVersion, List<StagedChunk> chunks, List<float[]> embeddings) {
            DocumentEntity doc = documentRepository.findById(documentId).orElse(null);
            if (doc != null && (doc.getProcessingVersion() != workerVersion || doc.getStatus() != DocumentStatus.PROCESSING)) {
                return 0;
            }
            for (int i = 0; i < chunks.size(); i++) {
                StagedChunk chunk = chunks.get(i);
                float[] vec = embeddings.get(i);
                stageStore.put(chunk.id(), new StagedChunk(
                        chunk.id(), chunk.documentId(), chunk.chunkIndex(), chunk.content(), vec, chunk.createdAt()
                ));
            }
            return chunks.size();
        }

        @Override
        public int copyStagedToPublished(Long documentId, Long projectId) {
            List<StagedChunk> matching = findAllStagedChunks(documentId);
            List<DocumentChunk> toPublish = matching.stream()
                    .map(s -> new DocumentChunk(s.id(), documentId, projectId, s.chunkIndex(), s.content(), s.embedding(), s.createdAt()))
                    .toList();
            publishedRepository.saveAll(toPublish);
            return toPublish.size();
        }

        @Override
        public void deleteStagedChunks(Long documentId) {
            stageStore.values().removeIf(c -> Objects.equals(c.documentId(), documentId));
        }

        @Override
        public void deleteAllByDocumentId(Long documentId) {
            stageStore.values().removeIf(c -> Objects.equals(c.documentId(), documentId));
        }
    }
}
