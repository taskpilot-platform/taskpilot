package com.taskpilot.ai.rag;

import com.taskpilot.ai.rag.service.GoogleAiEmbeddingServiceImpl;
import com.taskpilot.ai.rag.service.LangChain4jDocumentChunker;
import com.taskpilot.ai.rag.service.TikaDocumentTextExtractor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RealDocumentPipelineVerificationTest {

    private TikaDocumentTextExtractor textExtractor;
    private LangChain4jDocumentChunker chunker;

    @BeforeEach
    void setUp() {
        textExtractor = new TikaDocumentTextExtractor();
        chunker = new LangChain4jDocumentChunker(700, 100);
    }

    @Test
    @DisplayName("Verify Apache Tika extracts text from deterministic DOCX fixture")
    void testRealDocxParsing(@TempDir Path tempDir) throws Exception {
        Path docxPath = writeMinimalDocxFixture(
                tempDir.resolve("taskpilot-fixture.docx"),
                "TaskPilot RAG pipeline DOCX parsing verification fixture."
        );

        try (InputStream is = Files.newInputStream(docxPath)) {
            String extractedText = textExtractor.extractText(is, docxPath.getFileName().toString(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

            assertThat(extractedText).isNotNull().isNotBlank();
            assertThat(extractedText).containsIgnoringCase("TaskPilot");
        }
    }

    @Test
    @DisplayName("Verify Apache Tika extracts text from deterministic Markdown fixture")
    void testRealMarkdownParsing(@TempDir Path tempDir) throws Exception {
        Path mdPath = tempDir.resolve("taskpilot-architecture-fixture.md");
        Files.writeString(
                mdPath,
                "# TaskPilot Architecture Fixture\n\nDeterministic Markdown extraction verification for TaskPilot.\n",
                StandardCharsets.UTF_8
        );

        try (InputStream is = Files.newInputStream(mdPath)) {
            String extractedText = textExtractor.extractText(is, mdPath.getFileName().toString(), "text/markdown");

            assertThat(extractedText).isNotNull().isNotBlank();
            assertThat(extractedText).containsIgnoringCase("TaskPilot");
        }
    }

    @Test
    @Tag("provider-live")
    @DisplayName("Verify End-to-End Real Document RAG Pipeline: Tika -> Chunker -> Gemini Embedding 2 (768-dim) -> Semantic Similarity Search")
    void testEndToEndPipelineWithRealDocument() throws Exception {
        String apiKey = resolveGeminiApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY is not configured; skipping live model verification");

        Path docxPath = findWorkspaceFile("DeCuongChiTiet_DoAn2_TaskPilot_revised.docx");
        if (docxPath == null || !Files.exists(docxPath)) {
            docxPath = findWorkspaceFile("architecture.md");
        }
        Assumptions.assumeTrue(docxPath != null && Files.exists(docxPath), "No sample workspace document found for E2E verification");

        // 1. Apache Tika Text Extraction
        String extractedText;
        try (InputStream is = Files.newInputStream(docxPath)) {
            extractedText = textExtractor.extractText(is, docxPath.getFileName().toString(), null);
        }
        assertThat(extractedText).isNotNull().isNotBlank();
        System.out.println("E2E Document loaded: " + docxPath.getFileName() + " (" + extractedText.length() + " chars)");

        // 2. Recursive Chunking (700 chars, 100 overlap)
        List<String> chunks = chunker.chunkText(extractedText);
        assertThat(chunks).isNotEmpty();
        System.out.println("E2E Generated " + chunks.size() + " text chunks");

        // Select a subset of chunks to embed (up to 8 chunks to preserve API quota)
        int testChunkCount = Math.min(8, chunks.size());
        List<String> sampleChunks = chunks.subList(0, testChunkCount);

        // 3. Live Canonical Embedding with gemini-embedding-2 (768 dimensions)
        GoogleAiEmbeddingServiceImpl embeddingService = new GoogleAiEmbeddingServiceImpl(apiKey, "gemini-embedding-2", 768);
        assertThat(embeddingService.getDimension()).isEqualTo(768);

        long startEmbed = System.currentTimeMillis();
        List<float[]> chunkVectors = embeddingService.embedBatch(sampleChunks);
        long embedDuration = System.currentTimeMillis() - startEmbed;

        assertThat(chunkVectors).hasSize(testChunkCount);
        for (float[] vec : chunkVectors) {
            assertThat(vec).isNotNull();
            assertThat(vec.length).isEqualTo(768);
        }
        System.out.println("E2E Embedded " + testChunkCount + " chunks in " + embedDuration + "ms. Vector dimension: 768.");

        // 4. Test Semantic Query Embedding and Cosine Similarity Ranking
        String relevantQuery = "Mục tiêu xây dựng hệ thống quản lý dự án TaskPilot";
        float[] queryVector = embeddingService.embedText(relevantQuery);
        assertThat(queryVector.length).isEqualTo(768);

        double highestSimilarity = -1.0;
        int bestChunkIndex = -1;

        for (int i = 0; i < chunkVectors.size(); i++) {
            double sim = cosineSimilarity(queryVector, chunkVectors.get(i));
            if (sim > highestSimilarity) {
                highestSimilarity = sim;
                bestChunkIndex = i;
            }
        }

        System.out.println("Query: \"" + relevantQuery + "\"");
        System.out.println("Top Match Chunk #" + bestChunkIndex + " Cosine Similarity: " + String.format("%.4f", highestSimilarity));
        String bestSnippet = sampleChunks.get(bestChunkIndex);
        System.out.println("Top Match Snippet: " + bestSnippet.substring(0, Math.min(180, bestSnippet.length())).replace("\n", " ") + "...");

        // Semantic check: Relevant query should have strong cosine similarity (> 0.45)
        assertThat(highestSimilarity).isGreaterThan(0.45);

        // 5. Contrast with Unrelated Query (e.g., cooking pizza)
        String unrelatedQuery = "Công thức nướng bánh pizza hải sản phô mai tại nhà";
        float[] unrelatedVector = embeddingService.embedText(unrelatedQuery);
        double unrelatedHighestSim = -1.0;
        for (float[] vec : chunkVectors) {
            double sim = cosineSimilarity(unrelatedVector, vec);
            if (sim > unrelatedHighestSim) {
                unrelatedHighestSim = sim;
            }
        }
        System.out.println("Unrelated Query Top Similarity: " + String.format("%.4f", unrelatedHighestSim));

        // Semantic discrimination: Relevant query similarity must be notably higher than unrelated query
        assertThat(highestSimilarity)
                .as("Relevant document query must yield higher cosine similarity than completely unrelated query")
                .isGreaterThan(unrelatedHighestSim);
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector length mismatch");
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private Path writeMinimalDocxFixture(Path targetPath, String bodyText) throws IOException {
        String contentTypesXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """;
        String relsXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """;
        String escapedBody = bodyText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:r>
                        <w:t>%s</w:t>
                      </w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """.formatted(escapedBody);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(targetPath))) {
            writeZipEntry(zip, "[Content_Types].xml", contentTypesXml);
            writeZipEntry(zip, "_rels/.rels", relsXml);
            writeZipEntry(zip, "word/document.xml", documentXml);
        }
        return targetPath;
    }

    private void writeZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private Path findWorkspaceFile(String filename) {
        List<Path> searchPaths = List.of(
                Path.of(filename),
                Path.of("..", filename),
                Path.of("../..", filename),
                Path.of("d:/HK6-UIT/DA1", filename)
        );
        for (Path p : searchPaths) {
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    private String resolveGeminiApiKey() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        key = System.getProperty("GEMINI_API_KEY");
        if (key != null && !key.isBlank()) {
            return key.trim();
        }

        List<Path> potentialPaths = List.of(
                Path.of(".env"),
                Path.of("../.env"),
                Path.of("../../.env"),
                Path.of("d:/HK6-UIT/DA1/taskpilot/.env")
        );

        for (Path path : potentialPaths) {
            if (Files.exists(path)) {
                try {
                    for (String line : Files.readAllLines(path)) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("GEMINI_API_KEY=") && !trimmed.startsWith("#")) {
                            return trimmed.substring("GEMINI_API_KEY=".length()).trim();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}
