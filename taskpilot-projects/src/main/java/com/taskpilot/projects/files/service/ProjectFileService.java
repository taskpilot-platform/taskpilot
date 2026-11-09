package com.taskpilot.projects.files.service;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveFileDto;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.files.dto.FileDownloadResource;
import com.taskpilot.projects.files.dto.ProjectFileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectFileService {

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final String DEFAULT_STORAGE_BUCKET = "documents";

    private final ProjectFileRepository projectFileRepository;
    private final StorageService storageService;
    private final GoogleDriveService googleDriveService;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserIdentityPort userIdentityPort;
    private final UserProfilePort userProfilePort;

    @Transactional
    public ProjectFileResponse uploadFile(Long projectId, String userEmail, MultipartFile file, String description) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "File cannot be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "File size exceeds 50MB limit");
        }

        Long userId = getUserId(userEmail);
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "unnamed_file";
        }

        String folder = "projects/" + projectId + "/files";
        String storageKey;
        String storageBucket = DEFAULT_STORAGE_BUCKET;
        String updatedDescription = description;

        boolean isVideoOrRecording = (file.getContentType() != null && file.getContentType().startsWith("video/"))
                || originalName.endsWith(".webm")
                || originalName.endsWith(".mp4")
                || (description != null && description.contains("cuộc họp"));

        if (googleDriveService != null && googleDriveService.isConfigured() && isVideoOrRecording) {
            try {
                GoogleDriveFileDto driveDto = googleDriveService.uploadFile(file);
                storageKey = driveDto.getFileId();
                storageBucket = "googledrive";
                if (updatedDescription == null || updatedDescription.isBlank()) {
                    updatedDescription = driveDto.getWebViewLink();
                } else if (!updatedDescription.contains(driveDto.getWebViewLink())) {
                    updatedDescription = updatedDescription + " | " + driveDto.getWebViewLink();
                }
                log.info("Uploaded video/recording to Google Drive 5TB: fileId={}, link={}", driveDto.getFileId(), driveDto.getWebViewLink());
            } catch (Exception driveEx) {
                log.warn("Google Drive upload failed, falling back to S3: {}", driveEx.getMessage());
                try {
                    storageKey = storageService.uploadFile(file, folder, DEFAULT_STORAGE_BUCKET);
                } catch (IOException e) {
                    log.error("Failed to upload file to S3: {}", e.getMessage(), e);
                    throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Failed to upload file to storage");
                }
            }
        } else {
            try {
                storageKey = storageService.uploadFile(file, folder, DEFAULT_STORAGE_BUCKET);
            } catch (IOException e) {
                if (googleDriveService != null && googleDriveService.isConfigured()) {
                    try {
                        GoogleDriveFileDto driveDto = googleDriveService.uploadFile(file);
                        storageKey = driveDto.getFileId();
                        storageBucket = "googledrive";
                        updatedDescription = (updatedDescription != null ? updatedDescription + " | " : "") + driveDto.getWebViewLink();
                        log.info("S3 upload failed, automatic fallback to Google Drive 5TB succeeded: fileId={}", driveDto.getFileId());
                    } catch (Exception driveEx) {
                        log.error("Both S3 and Google Drive failed: {}", driveEx.getMessage());
                        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Failed to upload file to storage");
                    }
                } else {
                    log.error("Failed to upload file to S3: {}", e.getMessage(), e);
                    throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Failed to upload file to storage");
                }
            }
        }

        ProjectFileEntity entity = ProjectFileEntity.builder()
                .projectId(projectId)
                .uploaderId(userId)
                .fileName(originalName)
                .originalName(originalName)
                .fileSize(file.getSize())
                .contentType(file.getContentType())
                .storageKey(storageKey)
                .storageBucket(storageBucket)
                .description(updatedDescription)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        ProjectFileEntity saved = projectFileRepository.save(entity);
        String uploaderName = getUserName(userId);
        return ProjectFileResponse.fromEntity(saved, uploaderName, userEmail);
    }

    @Transactional(readOnly = true)
    public Page<ProjectFileResponse> listFiles(Long projectId, String userEmail, String keyword, Pageable pageable) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        Page<ProjectFileEntity> page;
        if (keyword != null && !keyword.isBlank()) {
            page = projectFileRepository.findByProjectIdAndOriginalNameContainingIgnoreCase(projectId, keyword.trim(), pageable);
        } else {
            page = projectFileRepository.findByProjectId(projectId, pageable);
        }

        Set<Long> uploaderIds = page.getContent().stream()
                .map(ProjectFileEntity::getUploaderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> userNames = resolveUserNames(uploaderIds);

        return page.map(file -> {
            String name = userNames.getOrDefault(file.getUploaderId(), "Unknown User");
            return ProjectFileResponse.fromEntity(file, name, null);
        });
    }

    @Transactional(readOnly = true)
    public FileDownloadResource downloadFile(Long projectId, Long fileId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectFileEntity entity = projectFileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "File not found"));

        try {
            InputStream is;
            if ("googledrive".equalsIgnoreCase(entity.getStorageBucket()) && googleDriveService != null) {
                is = googleDriveService.downloadFile(entity.getStorageKey());
            } else {
                is = storageService.downloadFile(entity.getStorageBucket(), entity.getStorageKey());
            }
            return new FileDownloadResource(entity.getOriginalName(), entity.getContentType(), entity.getFileSize(), is);
        } catch (IOException e) {
            log.error("Failed to download file from storage: {}", e.getMessage(), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Failed to download file from storage");
        }
    }

    @Transactional
    public void deleteFile(Long projectId, Long fileId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectFileEntity entity = projectFileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "File not found"));

        // RBAC: Must be uploader OR Project Manager
        boolean isUploader = entity.getUploaderId() != null && entity.getUploaderId().equals(userId);
        if (!isUploader) {
            ProjectMemberEntity member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(), "Not a member"));
            if (member.getRole() != MemberRole.MANAGER) {
                throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Only the uploader or project manager can delete this file");
            }
        }

        try {
            if ("googledrive".equalsIgnoreCase(entity.getStorageBucket()) && googleDriveService != null) {
                googleDriveService.deleteFile(entity.getStorageKey());
            } else {
                storageService.deleteFile(entity.getStorageBucket(), entity.getStorageKey());
            }
        } catch (Exception e) {
            log.warn("Delete failed for key {}: {}", entity.getStorageKey(), e.getMessage());
        }

        projectFileRepository.delete(entity);
    }

    private Long getUserId(String email) {
        return userIdentityPort.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"))
                .id();
    }

    private String getUserName(Long userId) {
        if (userId == null) return "Unknown";
        return userProfilePort.findLiteById(userId)
                .map(UserProfileLiteDto::fullName)
                .orElse("User #" + userId);
    }

    private Map<Long, String> resolveUserNames(Set<Long> userIds) {
        if (userIds.isEmpty()) return Collections.emptyMap();
        List<UserProfileLiteDto> profiles = userProfilePort.findLiteByIds(userIds);
        Map<Long, String> map = new HashMap<>();
        for (UserProfileLiteDto p : profiles) {
            map.put(p.id(), p.fullName());
        }
        return map;
    }
}
