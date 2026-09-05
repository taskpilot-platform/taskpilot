package com.taskpilot.ai.rag;

import com.taskpilot.ai.rag.domain.DocumentChunk;
import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDomainTest {

    @Test
    @DisplayName("Verify DocumentEntity builder and lifecycle state transitions")
    void testDocumentLifecycleTransitions() {
        DocumentEntity doc = DocumentEntity.builder()
                .id(1L)
                .projectId(10L)
                .storageKey("documents/reqs.pdf")
                .originalFilename("reqs.pdf")
                .contentType("application/pdf")
                .fileSize(1024L)
                .status(DocumentStatus.UPLOADING)
                .createdBy(5L)
                .build();

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.UPLOADING);

        // Transition to PROCESSING
        doc.setStatus(DocumentStatus.PROCESSING);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSING);

        // Transition to READY
        doc.setStatus(DocumentStatus.READY);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.READY);

        // Transition to FAILED with error message
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorMessage("Parsing failed: corrupt PDF stream");
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(doc.getErrorMessage()).contains("corrupt PDF");
    }

    @Test
    @DisplayName("Verify DocumentChunk and ScoredChunk records")
    void testDocumentChunkRecords() {
        float[] vector = new float[768];
        vector[0] = 0.5f;

        DocumentChunk chunk = new DocumentChunk(
                100L,
                1L,
                10L,
                0,
                "Authentication requirements for OAuth2",
                vector,
                Instant.now()
        );

        assertThat(chunk.chunkIndex()).isEqualTo(0);
        assertThat(chunk.projectId()).isEqualTo(10L);
        assertThat(chunk.documentId()).isEqualTo(1L);
        assertThat(chunk.embedding()).hasSize(768);

        ScoredChunk scored = new ScoredChunk(
                100L,
                1L,
                10L,
                0,
                chunk.content(),
                0.92
        );

        assertThat(scored.similarity()).isEqualTo(0.92);
        assertThat(scored.content()).isEqualTo(chunk.content());
    }
}
