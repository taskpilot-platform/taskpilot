package com.taskpilot.projects.files.adapter;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.contracts.workspace.file.FileAccessLinkDto;
import com.taskpilot.contracts.workspace.file.FileCategory;
import com.taskpilot.contracts.workspace.file.ProjectFileDetailDto;
import com.taskpilot.contracts.workspace.file.ProjectFilePort;
import com.taskpilot.contracts.workspace.file.ProjectFileSummaryDto;
import com.taskpilot.contracts.workspace.file.StorageTier;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectFileAdapter implements ProjectFilePort {

    private final ProjectFileRepository projectFileRepository;
    private final ProjectSecurityService projectSecurityService;
    private final UserProfilePort userProfilePort;

    @Override
    public List<ProjectFileSummaryDto> listProjectFiles(Long projectId, FileCategory category, int limit, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        int resolvedLimit = Math.min(Math.max(limit, 1), 100);
        Page<ProjectFileEntity> page = projectFileRepository.findByProjectIdOrderByCreatedAtDesc(
                projectId, PageRequest.of(0, category != null ? Math.min(resolvedLimit * 2, 100) : resolvedLimit));

        List<ProjectFileEntity> list = page.getContent();
        if (category != null) {
            list = list.stream()
                    .filter(f -> FileCategory.fromMimeAndName(f.getContentType(), f.getOriginalName()) == category)
                    .limit(resolvedLimit)
                    .toList();
        }

        Map<Long, String> uploaderNames = resolveUploaderNames(list);
        return list.stream()
                .map(f -> toSummaryDto(f, uploaderNames.getOrDefault(f.getUploaderId(), "Unknown")))
                .toList();
    }

    @Override
    public List<ProjectFileSummaryDto> searchProjectFiles(Long projectId, String keyword, FileCategory category, int limit, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        int resolvedLimit = Math.min(Math.max(limit, 1), 100);
        Page<ProjectFileEntity> page;
        if (keyword != null && !keyword.isBlank()) {
            page = projectFileRepository.findByProjectIdAndOriginalNameContainingIgnoreCaseOrderByCreatedAtDesc(
                    projectId, keyword.trim(), PageRequest.of(0, category != null ? Math.min(resolvedLimit * 2, 100) : resolvedLimit));
        } else {
            page = projectFileRepository.findByProjectIdOrderByCreatedAtDesc(
                    projectId, PageRequest.of(0, category != null ? Math.min(resolvedLimit * 2, 100) : resolvedLimit));
        }

        List<ProjectFileEntity> list = page.getContent();
        if (category != null) {
            list = list.stream()
                    .filter(f -> FileCategory.fromMimeAndName(f.getContentType(), f.getOriginalName()) == category)
                    .limit(resolvedLimit)
                    .toList();
        }

        Map<Long, String> uploaderNames = resolveUploaderNames(list);
        return list.stream()
                .map(f -> toSummaryDto(f, uploaderNames.getOrDefault(f.getUploaderId(), "Unknown")))
                .toList();
    }

    @Override
    public ProjectFileDetailDto getProjectFileDetails(Long projectId, Long fileId, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        ProjectFileEntity file = projectFileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "File not found: " + fileId));

        String uploaderName = "Unknown";
        if (file.getUploaderId() != null) {
            UserProfileLiteDto profile = userProfilePort.findLiteById(file.getUploaderId()).orElse(null);
            if (profile != null) {
                uploaderName = profile.fullName();
            }
        }

        ProjectFileSummaryDto summary = toSummaryDto(file, uploaderName);
        FileAccessLinkDto link = getFileAccessLink(projectId, fileId, requesterUserId);

        return new ProjectFileDetailDto(summary, null, null, link);
    }

    @Override
    public FileAccessLinkDto getFileAccessLink(Long projectId, Long fileId, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        ProjectFileEntity file = projectFileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "File not found: " + fileId));

        String appRoute = "/projects/" + projectId + "/files";
        String downloadApi = "/api/projects/" + projectId + "/files/" + fileId + "/download";

        return new FileAccessLinkDto(
                file.getId(),
                appRoute,
                downloadApi,
                true
        );
    }

    private void validateAccess(Long projectId, Long userId) {
        projectSecurityService.requireProject(projectId);
        if (userId != null) {
            projectSecurityService.validateMember(projectId, userId);
        }
    }

    private ProjectFileSummaryDto toSummaryDto(ProjectFileEntity f, String uploaderName) {
        boolean isDrive = "googledrive".equalsIgnoreCase(f.getStorageBucket());
        return new ProjectFileSummaryDto(
                f.getId(),
                f.getProjectId(),
                f.getOriginalName(),
                FileCategory.fromMimeAndName(f.getContentType(), f.getOriginalName()),
                f.getContentType(),
                f.getFileSize() != null ? f.getFileSize() : 0L,
                formatSize(f.getFileSize() != null ? f.getFileSize() : 0L),
                f.getUploaderId(),
                uploaderName,
                f.getDescription(),
                f.getCreatedAt(),
                isDrive ? StorageTier.GOOGLE_DRIVE : StorageTier.SUPABASE
        );
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.ROOT, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private Map<Long, String> resolveUploaderNames(List<ProjectFileEntity> files) {
        Set<Long> uploaderIds = files.stream()
                .map(ProjectFileEntity::getUploaderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (uploaderIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<UserProfileLiteDto> profiles = userProfilePort.findLiteByIds(uploaderIds);
        Map<Long, String> map = new HashMap<>();
        for (UserProfileLiteDto p : profiles) {
            map.put(p.id(), p.fullName());
        }
        return map;
    }
}
