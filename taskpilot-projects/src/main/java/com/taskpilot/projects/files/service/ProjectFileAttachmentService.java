package com.taskpilot.projects.files.service;

import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.MeetingRecordingEntity.State;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.repository.MeetingRecordingRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Dedicated Spring bean owning the transactional metadata attach boundary for project files
 * so callers invoke it through a real Spring proxy rather than self-invocation.
 */
@Service
@RequiredArgsConstructor
public class ProjectFileAttachmentService {
    private static final Pattern RECORDING_NAME = Pattern.compile(
            "meeting_([0-9]+)_rec_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})[.](webm|mp4)");


    private final ProjectFileRepository projectFileRepository;
    private final com.taskpilot.projects.common.repository.ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;
    private final StorageUploadOperationPort uploadOperationPort;
    private final MeetingRecordingRepository recordings;

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

        validateRecordingAttachment(projectId, userId, originalName);
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
                .storageProvider("googledrive".equalsIgnoreCase(storageBucket) ? "gdrive" : "s3")
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

    private void validateRecordingAttachment(Long projectId, Long userId, String name) {
        var match = RECORDING_NAME.matcher(name);
        if (!match.matches()) return;

        var recording = recordings.findById(UUID.fromString(match.group(2)))
                .orElseThrow(() -> new BusinessException(404, "Recording not found"));
        if (!Objects.equals(recording.getProjectId(), projectId) || !Objects.equals(recording.getRecorderId(), userId)
                || !recording.getMeetingId().toString().equals(match.group(1))) {
            throw new BusinessException(404, "Recording not found");
        }
        // The existing project write lock serializes metadata attachment across concurrent requests.
        String prefix = name.substring(0, name.lastIndexOf('.') + 1);
        if (projectFileRepository.existsByProjectIdAndUploaderIdAndOriginalNameIn(projectId, userId,
                List.of(prefix + "webm", prefix + "mp4"))) {
            throw new BusinessException(409, "This recording already has an uploaded file");
        }
        if (recording.getState() != State.UPLOADING) {
            throw new BusinessException(409, "This recording cannot receive a file");
        }
    }
}
