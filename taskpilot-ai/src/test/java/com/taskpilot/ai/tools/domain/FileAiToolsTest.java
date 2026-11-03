package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.workspace.file.FileAccessLinkDto;
import com.taskpilot.contracts.workspace.file.FileCategory;
import com.taskpilot.contracts.workspace.file.ProjectFileDetailDto;
import com.taskpilot.contracts.workspace.file.ProjectFilePort;
import com.taskpilot.contracts.workspace.file.ProjectFileSummaryDto;
import com.taskpilot.contracts.workspace.file.StorageTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileAiToolsTest {

    private ProjectFilePort projectFilePort;
    private FileAiTools fileAiTools;

    @BeforeEach
    void setUp() {
        projectFilePort = Mockito.mock(ProjectFilePort.class);
        fileAiTools = new FileAiTools(projectFilePort);
        ToolExecutionContext.set(new ToolExecutionContext.Context(100L, 200L, "test"));
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void testListProjectFiles_Success() {
        ProjectFileSummaryDto file = new ProjectFileSummaryDto(
                1L, 10L, "spec.pdf", FileCategory.DOCUMENT, "application/pdf", 1024L, "1.0 KB",
                100L, "Alice", "Spec doc", Instant.now(), StorageTier.SUPABASE);
        when(projectFilePort.listProjectFiles(eq(10L), eq(FileCategory.DOCUMENT), eq(5), eq(100L)))
                .thenReturn(List.of(file));

        Object result = fileAiTools.listProjectFiles(10L, "document", 5);
        assertThat(result).isInstanceOf(List.class);
        List<?> list = (List<?>) result;
        assertThat(list).hasSize(1);
        verify(projectFilePort).listProjectFiles(10L, FileCategory.DOCUMENT, 5, 100L);
    }

    @Test
    void testListProjectFiles_Empty() {
        when(projectFilePort.listProjectFiles(eq(10L), any(), anyInt(), eq(100L)))
                .thenReturn(List.of());

        Object result = fileAiTools.listProjectFiles(10L, null, 10);
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("hiện không có tệp nào");
    }

    @Test
    void testListProjectFiles_MissingProjectId() {
        Object result = fileAiTools.listProjectFiles(null, null, null);
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("Project ID is required");
    }

    @Test
    void testSearchProjectFiles_Success() {
        ProjectFileSummaryDto file = new ProjectFileSummaryDto(
                2L, 10L, "architecture.png", FileCategory.IMAGE, "image/png", 2048L, "2.0 KB",
                100L, "Bob", "Arch diagram", Instant.now(), StorageTier.GOOGLE_DRIVE);
        when(projectFilePort.searchProjectFiles(eq(10L), eq("arch"), any(), anyInt(), eq(100L)))
                .thenReturn(List.of(file));

        Object result = fileAiTools.searchProjectFiles(10L, "arch", null, null);
        assertThat(result).isInstanceOf(List.class);
        List<?> list = (List<?>) result;
        assertThat(list).hasSize(1);
    }

    @Test
    void testSearchProjectFiles_Empty() {
        when(projectFilePort.searchProjectFiles(eq(10L), eq("nonexistent"), any(), anyInt(), eq(100L)))
                .thenReturn(List.of());

        Object result = fileAiTools.searchProjectFiles(10L, "nonexistent", null, null);
        assertThat((String) result).contains("Không tìm thấy tệp nào phù hợp");
    }

    @Test
    void testGetProjectFileDetails_Success() {
        ProjectFileSummaryDto summary = new ProjectFileSummaryDto(
                1L, 10L, "spec.pdf", FileCategory.DOCUMENT, "application/pdf", 1024L, "1.0 KB",
                100L, "Alice", "Spec doc", Instant.now(), StorageTier.SUPABASE);
        FileAccessLinkDto link = new FileAccessLinkDto(1L, "/projects/10/files", "/api/projects/10/files/1/download", true);
        ProjectFileDetailDto detail = new ProjectFileDetailDto(summary, null, null, link);

        when(projectFilePort.getProjectFileDetails(10L, 1L, 100L)).thenReturn(detail);

        Object result = fileAiTools.getProjectFileDetails(10L, 1L);
        assertThat(result).isInstanceOf(ProjectFileDetailDto.class);
        assertThat(((ProjectFileDetailDto) result).file().name()).isEqualTo("spec.pdf");
    }

    @Test
    void testGetProjectFileLink_Success() {
        FileAccessLinkDto link = new FileAccessLinkDto(1L, "/projects/10/files", "/api/projects/10/files/1/download", true);
        when(projectFilePort.getFileAccessLink(10L, 1L, 100L)).thenReturn(link);

        Object result = fileAiTools.getProjectFileLink(10L, 1L);
        assertThat(result).isInstanceOf(FileAccessLinkDto.class);
        assertThat(((FileAccessLinkDto) result).downloadApi()).isEqualTo("/api/projects/10/files/1/download");
    }
}
