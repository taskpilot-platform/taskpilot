package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.workspace.file.FileAccessLinkDto;
import com.taskpilot.contracts.workspace.file.FileCategory;
import com.taskpilot.contracts.workspace.file.ProjectFileDetailDto;
import com.taskpilot.contracts.workspace.file.ProjectFilePort;
import com.taskpilot.contracts.workspace.file.ProjectFileSummaryDto;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileAiTools {

    private final ProjectFilePort projectFilePort;

    @Tool("Liệt kê các tệp/tài liệu trong dự án theo danh mục (DOCUMENT, SPREADSHEET, PRESENTATION, IMAGE, VIDEO, AUDIO, ARCHIVE, CODE, RECORDING, OTHER) hoặc mới nhất")
    public Object listProjectFiles(
            @P("ID dự án cần xem danh sách tệp") Long projectId,
            @P("Optional. Danh mục tệp (ví dụ: DOCUMENT, SPREADSHEET, IMAGE, RECORDING)") String category,
            @P("Optional. Số lượng tệp tối đa (mặc định 10, tối đa 50)") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] listProjectFiles called for user={}, projectId={}, category={}, limit={}",
                userId, projectId, category, limit);

        if (projectId == null) {
            return "Project ID is required to list project files.";
        }

        FileCategory fileCat = FileCategory.fromParam(category);
        int maxLimit = limit != null && limit > 0 ? Math.min(limit, 50) : 10;
        List<ProjectFileSummaryDto> files = projectFilePort.listProjectFiles(projectId, fileCat, maxLimit, userId);

        if (files.isEmpty()) {
            return "Dự án #" + projectId + " hiện không có tệp nào" + (fileCat != null ? " thuộc danh mục " + fileCat : "") + ".";
        }

        return files;
    }

    @Tool("Tìm kiếm tệp trong dự án theo từ khóa tên tệp hoặc mô tả")
    public Object searchProjectFiles(
            @P("ID dự án cần tìm kiếm tệp") Long projectId,
            @P("Từ khóa tìm kiếm (tên tệp hoặc định dạng như pdf, docx, png)") String keyword,
            @P("Optional. Danh mục tệp để lọc kết quả") String category,
            @P("Optional. Số lượng tệp tối đa (mặc định 10, tối đa 50)") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] searchProjectFiles called for user={}, projectId={}, keyword='{}', category={}, limit={}",
                userId, projectId, keyword, category, limit);

        if (projectId == null) {
            return "Project ID is required to search project files.";
        }

        FileCategory fileCat = FileCategory.fromParam(category);
        int maxLimit = limit != null && limit > 0 ? Math.min(limit, 50) : 10;
        List<ProjectFileSummaryDto> files = projectFilePort.searchProjectFiles(projectId, keyword, fileCat, maxLimit, userId);

        if (files.isEmpty()) {
            return "Không tìm thấy tệp nào phù hợp với từ khóa '" + keyword + "' trong dự án #" + projectId + ".";
        }

        return files;
    }

    @Tool("Lấy thông tin chi tiết của một tệp trong dự án bao gồm kích thước, loại tệp, người tải lên và liên kết tải/xem")
    public Object getProjectFileDetails(
            @P("ID dự án chứa tệp") Long projectId,
            @P("ID của tệp cần xem chi tiết") Long fileId) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] getProjectFileDetails called for user={}, projectId={}, fileId={}",
                userId, projectId, fileId);

        if (projectId == null) {
            return "Project ID is required to get file details.";
        }
        if (fileId == null) {
            return "File ID is required to get file details.";
        }

        try {
            return projectFilePort.getProjectFileDetails(projectId, fileId, userId);
        } catch (Exception e) {
            return "Không thể tải thông tin tệp: " + e.getMessage();
        }
    }

    @Tool("Lấy đường dẫn liên kết tải xuống hoặc truy cập tệp trong dự án")
    public Object getProjectFileLink(
            @P("ID dự án chứa tệp") Long projectId,
            @P("ID của tệp cần lấy liên kết") Long fileId) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] getProjectFileLink called for user={}, projectId={}, fileId={}",
                userId, projectId, fileId);

        if (projectId == null) {
            return "Project ID is required to get file access link.";
        }
        if (fileId == null) {
            return "File ID is required to get file access link.";
        }

        try {
            return projectFilePort.getFileAccessLink(projectId, fileId, userId);
        } catch (Exception e) {
            return "Không thể lấy liên kết tệp: " + e.getMessage();
        }
    }
}
