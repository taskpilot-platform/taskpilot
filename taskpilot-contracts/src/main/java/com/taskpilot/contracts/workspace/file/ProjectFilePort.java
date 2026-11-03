package com.taskpilot.contracts.workspace.file;

import java.util.List;

public interface ProjectFilePort {

    List<ProjectFileSummaryDto> listProjectFiles(Long projectId, FileCategory category, int limit, Long requesterUserId);

    List<ProjectFileSummaryDto> searchProjectFiles(Long projectId, String keyword, FileCategory category, int limit, Long requesterUserId);

    ProjectFileDetailDto getProjectFileDetails(Long projectId, Long fileId, Long requesterUserId);

    FileAccessLinkDto getFileAccessLink(Long projectId, Long fileId, Long requesterUserId);
}
