package com.taskpilot.ai.rag;

import com.taskpilot.ai.rag.service.TikaDocumentTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TikaDocumentTextExtractorTest {

    private TikaDocumentTextExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new TikaDocumentTextExtractor();
    }

    @Test
    @DisplayName("Verify plain text extraction from stream")
    void testExtractPlainText() {
        String content = "Project TaskPilot requires OAuth2 authentication and pgvector RAG subsystem.";
        ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extractText(stream, "spec.txt", "text/plain");
        assertThat(result).contains("OAuth2 authentication");
        assertThat(result).contains("pgvector RAG subsystem");
    }

    @Test
    @DisplayName("Verify markdown text extraction from stream")
    void testExtractMarkdownText() {
        String content = "# Architecture Document\n\nTaskPilot provides intelligent multi-agent task distribution.\n\n- Point 1\n- Point 2";
        ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extractText(stream, "architecture.md", "text/markdown");
        assertThat(result).contains("Architecture Document");
        assertThat(result).contains("intelligent multi-agent");
    }

    @Test
    @DisplayName("Verify CSV text extraction from stream")
    void testExtractCsvText() {
        String content = "id,name,role\n1,Alice,Manager\n2,Bob,Developer\n";
        ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extractText(stream, "team.csv", "text/csv");
        assertThat(result).contains("Alice");
        assertThat(result).contains("Bob");
    }

    @Test
    @DisplayName("Verify null stream throws IllegalArgumentException")
    void testExtractNullStream() {
        assertThatThrownBy(() -> extractor.extractText(null, "test.txt", "text/plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("InputStream must not be null");
    }

    @Test
    @DisplayName("Verify extraction beyond 100,000 characters does not truncate")
    void testExtractLargeTextBeyond100kLimit() {
        StringBuilder sb = new StringBuilder();
        sb.append("START_OF_LARGE_DOC ");
        while (sb.length() < 150_000) {
            sb.append("This is an extended section of TaskPilot documentation for large scale RAG verification. ");
        }
        sb.append(" END_OF_LARGE_DOC");
        String content = sb.toString();

        ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        String result = extractor.extractText(stream, "large_spec.txt", "text/plain");

        assertThat(result.length()).isGreaterThan(100_000);
        assertThat(result).contains("END_OF_LARGE_DOC");
    }

    @Test
    @DisplayName("Verify synthetic large PDF extraction beyond 100,000 characters contains beginning, middle, and end markers")
    void testExtractLargePdfDocumentBeyond100kLimit() throws Exception {
        byte[] pdfBytes = generateSyntheticPdfWithMarkers(120);
        ByteArrayInputStream stream = new ByteArrayInputStream(pdfBytes);

        String result = extractor.extractText(stream, "large_architecture.pdf", "application/pdf");

        assertThat(result.length())
                .as("Extracted character count should exceed the default Tika 100k limit")
                .isGreaterThan(100_000);

        assertThat(result)
                .as("Should contain start marker from page 1")
                .contains("LARGE_DOC_PAGE_001");

        assertThat(result)
                .as("Should contain page 46 milestone marker")
                .contains("LARGE_DOC_PAGE_046");

        assertThat(result)
                .as("Should contain middle marker from page 60")
                .contains("LARGE_DOC_PAGE_400");

        assertThat(result)
                .as("Should contain end marker from final page 120")
                .contains("LARGE_DOC_PAGE_800");
    }

    private byte[] generateSyntheticPdfWithMarkers(int pageCount) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.font.PDType1Font font =
                    new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
            for (int pageNum = 1; pageNum <= pageCount; pageNum++) {
                org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
                doc.addPage(page);
                try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 10);
                    cs.newLineAtOffset(50, 700);

                    if (pageNum == 1) {
                        cs.showText("LARGE_DOC_PAGE_001: Beginning of TaskPilot Master Architecture Specification.");
                    } else if (pageNum == 46) {
                        cs.showText("LARGE_DOC_PAGE_046: Critical milestone crossing the legacy 100,000 character limit.");
                    } else if (pageNum == 60) {
                        cs.showText("LARGE_DOC_PAGE_400: Midpoint section detailing RAG and intelligent agent allocation.");
                    } else if (pageNum == pageCount) {
                        cs.showText("LARGE_DOC_PAGE_800: Final chapter, concluding remarks, and system sign-off.");
                    } else {
                        cs.showText("Page " + pageNum + " content: TaskPilot provides intelligent multi-agent task distribution.");
                    }
                    cs.newLineAtOffset(0, -20);
                    for (int line = 1; line <= 15; line++) {
                        cs.showText("Paragraph line " + line + ": Robust distributed task management, resilient resumable staging, and pgvector HNSW indexing in TaskPilot platform.");
                        cs.newLineAtOffset(0, -15);
                    }
                    cs.endText();
                }
            }
            doc.save(baos);
        }
        return baos.toByteArray();
    }
}



