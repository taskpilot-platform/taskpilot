package com.taskpilot.projects.files.service;

import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Dedicated Spring bean owning the transactional metadata attach boundary for project files
 * so callers invoke it through a real Spring proxy rather than self-invocation.
 */
@Service
@RequiredArgsConstructor
public class ProjectFileAttachmentService {

    private final ProjectFileRepository projectFileRepository;
    private final com.taskpilot.projects.common.repository.ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;
    private final StorageUploadOperationPort uploadOperationPort;

    @Transactional
    public ProjectFileEntity attachUploadedFile(
            Long projectId,
            Long userId,
            String originalName,
            long fileSize,
            String contentType,
            String storageKey,
            String storageBucket,
            String userDescription,
            UUID operationId
    ) {
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() ->
                new com.taskpilot.infrastructure.exception.BusinessException(404,"Project not found"));
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        if (uploadOperationPort != null) {
            uploadOperationPort.registerRollbackCompensation(
                    operationId,
                    StorageObjectLocator.of(storageBucket, storageKey),
                    "METADATA_ROLLBACK",
                    "Project file attach transaction rolled back"
            );
        }

        Instant now = Instant.now();
        ProjectFileEntity entity = ProjectFileEntity.builder()
                .projectId(projectId)
                .uploaderId(userId)
                .fileName(originalName)
                .originalName(originalName)
                .fileSize(fileSize)
                .contentType(contentType)
                .storageKey(storageKey)
                .storageBucket(storageBucket)
                .description(userDescription)
                .createdAt(now)
                .updatedAt(now)
                .build();

        ProjectFileEntity saved = projectFileRepository.saveAndFlush(entity);
        if (uploadOperationPort != null && operationId != null) {
            uploadOperationPort.markAttachedInCurrentTransaction(operationId, saved.getId());
        }
        return saved;
    }
}
