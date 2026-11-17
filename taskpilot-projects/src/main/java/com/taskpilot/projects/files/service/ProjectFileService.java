package com.taskpilot.projects.files.service;

import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.storage.EgressS3StorageService;
import com.taskpilot.projects.files.dto.FileStreamResource;
import lombok.RequiredArgsConstructor;
import com.taskpilot.contracts.storage.StorageCleanupPort;
import com.taskpilot.contracts.storage.StorageCleanupRequest;
import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.contracts.storage.StorageTargetDomain;
import com.taskpilot.contracts.storage.StorageUploadOperationDto;
import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.contracts.storage.StorageUploadReservationCommand;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.StorageService;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveFileDto;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import com.taskpilot.infrastructure.storage.range.ByteRange;
import com.taskpilot.infrastructure.storage.range.RangeParseResult;
import com.taskpilot.infrastructure.storage.range.RangeParser;
import com.taskpilot.infrastructure.storage.range.StorageObjectStat;
import com.taskpilot.infrastructure.storage.range.StorageReadRequest;
import com.taskpilot.infrastructure.storage.range.StorageReadResult;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.files.dto.FileDownloadResource;
import com.taskpilot.projects.files.dto.ProjectFileDownloadResult;
import com.taskpilot.projects.files.dto.ProjectFileResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
public class ProjectFileService {

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024; // 50MiB
    private static final String DEFAULT_STORAGE_BUCKET = "documents";
    private static final String DRIVE_BUCKET = "googledrive";

    private final ProjectFileRepository projectFileRepository;
    private final StorageService storageService;
    private final GoogleDriveService googleDriveService;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserProfilePort userProfilePort;
    private final ProjectFileAttachmentService projectFileAttachmentService;
    private final StorageUploadOperationPort uploadOperationPort;
    private final StorageCleanupPort storageCleanupPort;

    public ProjectFileService(
            ProjectFileRepository projectFileRepository,
            StorageService storageService,
            GoogleDriveService googleDriveService,
            ProjectSecurityService projectSecurityService,
            ProjectMemberRepository projectMemberRepository,
            UserProfilePort userProfilePort) {
        this(projectFileRepository, storageService, googleDriveService, projectSecurityService,
                projectMemberRepository, userProfilePort, null, null, null);
    }

    @Autowired
    public ProjectFileService(
            ProjectFileRepository projectFileRepository,
            StorageService storageService,
            @Autowired(required = false) GoogleDriveService googleDriveService,
            ProjectSecurityService projectSecurityService,
            ProjectMemberRepository projectMemberRepository,
            UserProfilePort userProfilePort,
            ProjectFileAttachmentService projectFileAttachmentService,
            StorageUploadOperationPort uploadOperationPort,
            StorageCleanupPort storageCleanupPort) {
        this.projectFileRepository = projectFileRepository;
        this.storageService = storageService;
        this.googleDriveService = googleDriveService;
        this.projectSecurityService = projectSecurityService;
        this.projectMemberRepository = projectMemberRepository;
        this.userProfilePort = userProfilePort;
        this.projectFileAttachmentService = projectFileAttachmentService;
        this.uploadOperationPort = uploadOperationPort;
        this.storageCleanupPort = storageCleanupPort;
    }

    /**
     * Uploads a project file without holding a DB transaction open across external network I/O.
     * Reserves a durable upload operation before writing to provider, persists locator immediately,
     * attaches metadata via a proxied Spring transaction boundary, and compensates on any failure.
     */
    public ProjectFileResponse uploadFile(Long projectId, CurrentActor actor, MultipartFile file, String description) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "File cannot be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "File size exceeds 50MB limit");
        }

        Long userId = actor.userId();
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "unnamed_file";
        }

        String folder = "projects/" + projectId + "/files";
        boolean isVideoOrRecording = (file.getContentType() != null && file.getContentType().startsWith("video/"))
                || originalName.endsWith(".webm")
                || originalName.endsWith(".mp4")
                || (description != null && description.contains("cuộc họp"));

        UploadedObject uploaded = executeDurableProviderUpload(
                projectId, userId, file, originalName, folder, isVideoOrRecording);

        ProjectFileEntity saved;
        try {
            if (projectFileAttachmentService != null) {
                saved = projectFileAttachmentService.attachUploadedFile(
                        projectId,
                        userId,
                        originalName,
                        file.getSize(),
                        file.getContentType(),
                        uploaded.storageKey(),
                        uploaded.storageBucket(),
                        description,
                        uploaded.operationId()
                );
            } else {
                ProjectFileEntity entity = ProjectFileEntity.builder()
                        .projectId(projectId)
                        .uploaderId(userId)
                        .fileName(originalName)
                        .originalName(originalName)
                        .fileSize(file.getSize())
                        .contentType(file.getContentType())
                        .storageKey(uploaded.storageKey())
                        .storageBucket(uploaded.storageBucket())
                        .description(description)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                saved = projectFileRepository.save(entity);
            }
        } catch (RuntimeException ex) {
            if (uploadOperationPort != null) {
                uploadOperationPort.compensateFailedUpload(
                        uploaded.operationId(),
                        StorageObjectLocator.of(uploaded.storageBucket(), uploaded.storageKey()),
                        "METADATA_ATTACH_FAILED",
                        ex.getMessage()
                );
            }
            throw ex;
        }

        String uploaderName = getUserName(userId);
        return ProjectFileResponse.fromEntity(saved, uploaderName, actor.currentEmail());
    }

    private UploadedObject executeDurableProviderUpload(
            Long projectId,
            Long userId,
            MultipartFile file,
            String originalName,
            String folder,
            boolean preferDrive
    ) {
        if (preferDrive && googleDriveService != null && googleDriveService.isConfigured()) {
            UUID driveOpId = reserveOperation(
                    userId, projectId, StorageProviderKind.GOOGLE_DRIVE, DRIVE_BUCKET, null, file, originalName);
            try {
                GoogleDriveFileDto driveDto = googleDriveService.uploadFile(file, null, driveOpId);
                markProviderWritten(driveOpId, driveDto.getFileId());
                log.info("Uploaded video/recording privately to Google Drive: fileId={}", driveDto.getFileId());
                return new UploadedObject(driveDto.getFileId(), DRIVE_BUCKET, driveOpId);
            } catch (Exception driveEx) {
                log.warn("Google Drive upload failed, falling back to S3: {}", driveEx.getMessage());
                compensateOrMarkDriveFailure(driveOpId, driveEx);
                return uploadToS3WithReservation(projectId, userId, file, originalName, folder);
            }
        }

        try {
            return uploadToS3WithReservation(projectId, userId, file, originalName, folder);
        } catch (S3UploadException s3Ex) {
            if (googleDriveService != null && googleDriveService.isConfigured()) {
                UUID driveOpId = reserveOperation(
                        userId, projectId, StorageProviderKind.GOOGLE_DRIVE, DRIVE_BUCKET, null, file, originalName);
                try {
                    GoogleDriveFileDto driveDto = googleDriveService.uploadFile(file, null, driveOpId);
                    markProviderWritten(driveOpId, driveDto.getFileId());
                    log.info("S3 upload failed, private fallback to Google Drive succeeded: fileId={}", driveDto.getFileId());
                    return new UploadedObject(driveDto.getFileId(), DRIVE_BUCKET, driveOpId);
                } catch (Exception driveEx) {
                    log.error("Both S3 and Google Drive failed: {}", driveEx.getMessage());
                    compensateOrMarkDriveFailure(driveOpId, driveEx);
                    throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Failed to upload file to storage");
                }
            }
            log.error("Failed to upload file to S3: {}", s3Ex.getMessage(), s3Ex);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Failed to upload file to storage");
        }
    }

    private UploadedObject uploadToS3WithReservation(
            Long projectId,
            Long userId,
            MultipartFile file,
            String originalName,
            String folder
    ) {
        String plannedKey = storageService.planObjectKey(folder, originalName);
        if (plannedKey == null || plannedKey.isBlank()) {
            plannedKey = folder + "/" + UUID.randomUUID() + "-" + originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        }
        UUID s3OpId = reserveOperation(
                userId, projectId, StorageProviderKind.S3, DEFAULT_STORAGE_BUCKET, plannedKey, file, originalName);
        try {
            String storedKey = storageService.uploadFileAtKey(file, DEFAULT_STORAGE_BUCKET, plannedKey);
            String resolvedKey = (storedKey != null && !storedKey.isBlank()) ? storedKey : plannedKey;
            markProviderWritten(s3OpId, resolvedKey);
            return new UploadedObject(resolvedKey, DEFAULT_STORAGE_BUCKET, s3OpId);
        } catch (IOException | RuntimeException ex) {
            if (uploadOperationPort != null) {
                uploadOperationPort.compensateFailedUpload(
                        s3OpId,
                        StorageObjectLocator.of(DEFAULT_STORAGE_BUCKET, plannedKey),
                        "S3_UPLOAD_FAILED",
                        ex.getMessage()
                );
            }
            throw new S3UploadException("Failed to upload file to S3", ex);
        }
    }

    private UUID reserveOperation(
            Long userId,
            Long projectId,
            StorageProviderKind provider,
            String bucket,
            String plannedKey,
            MultipartFile file,
            String originalName
    ) {
        if (uploadOperationPort == null) {
            return null;
        }
        StorageUploadOperationDto op = uploadOperationPort.reserve(new StorageUploadReservationCommand(
                userId,
                projectId,
                StorageTargetDomain.PROJECT_FILE,
                provider,
                bucket,
                plannedKey,
                originalName,
                file.getContentType(),
                file.getSize()
        ));
        return op.operationId();
    }

    private void markProviderWritten(UUID operationId, String providerKey) {
        if (uploadOperationPort != null && operationId != null) {
            uploadOperationPort.markProviderWritten(operationId, providerKey);
        }
    }

    private void compensateOrMarkDriveFailure(UUID operationId, Exception ex) {
        if (uploadOperationPort != null && operationId != null) {
            uploadOperationPort.compensateFailedUpload(operationId, null, "DRIVE_UPLOAD_FAILED", ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<ProjectFileResponse> listFiles(Long projectId, Long userId, String keyword, Pageable pageable) {
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
    public FileDownloadResource downloadFile(Long projectId, Long fileId, Long userId) {
        ProjectFileDownloadResult result = downloadFileWithRange(projectId, fileId, userId, null, null);
        return new FileDownloadResource(result.fileName(), result.contentType(), result.totalBytes(), result.inputStream());
    }

    /**
     * Authorizes access first, evaluates optional single Range and If-Range preconditions,
     * and passes exact byte ranges directly to the underlying storage provider.
     */
    @Transactional(readOnly = true)
    public ProjectFileDownloadResult downloadFileWithRange(
            Long projectId,
            Long fileId,
            Long userId,
            String rangeHeader,
            String ifRangeHeader
    ) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectFileEntity entity = projectFileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "File not found"));

        boolean isDrive = DRIVE_BUCKET.equalsIgnoreCase(entity.getStorageBucket()) && googleDriveService != null;
        Optional<StorageObjectStat> stat = Optional.empty();
        long totalBytes = entity.getFileSize() != null ? entity.getFileSize() : -1L;

        boolean hasRange = rangeHeader != null && !rangeHeader.isBlank();
        // Drive media HTTP ETags are not the metadata md5 validator. Use metadata consistently,
        // including on the initial full response whose validator the client later sends in If-Range.
        if (isDrive || totalBytes < 0 || (hasRange && ifRangeHeader != null && !ifRangeHeader.isBlank())) {
            stat = fetchStatQuietly(isDrive, entity);
            if (totalBytes < 0 && stat.isPresent() && stat.get().totalBytes() >= 0) {
                totalBytes = stat.get().totalBytes();
            }
        }

        Optional<String> effectiveEtag = stat.flatMap(StorageObjectStat::etag)
                .or(() -> buildDeterministicStrongEtag(entity));
        Optional<Instant> effectiveLastModified = stat.flatMap(StorageObjectStat::lastModified)
                .or(() -> Optional.ofNullable(entity.getUpdatedAt()).or(() -> Optional.ofNullable(entity.getCreatedAt())));

        // If-Range is a precondition on applying Range at all, including unsatisfiable ranges.
        boolean ignoreRange = hasRange && ifRangeHeader != null && !ifRangeHeader.isBlank()
                && !RangeParser.matchesIfRange(ifRangeHeader, effectiveEtag, effectiveLastModified);
        RangeParseResult parsedRange = RangeParser.parse(ignoreRange ? null : rangeHeader, totalBytes);
        if (parsedRange.status() == RangeParseResult.Status.UNSATISFIABLE) {
            return new ProjectFileDownloadResult(
                    ProjectFileDownloadResult.Status.RANGE_NOT_SATISFIABLE_416,
                    entity.getOriginalName(),
                    entity.getContentType(),
                    Math.max(0L, totalBytes),
                    0L,
                    Optional.empty(),
                    effectiveEtag,
                    effectiveLastModified,
                    null
            );
        }

        Optional<ByteRange> requestedRange = parsedRange.status() == RangeParseResult.Status.SATISFIABLE
                ? parsedRange.range()
                : Optional.empty();

        try {
            if (requestedRange.isPresent()) {
                ByteRange range = requestedRange.get();
                StorageReadResult read = readFromProvider(isDrive, entity, Optional.of(range));
                long resolvedTotal = totalBytes >= 0 ? totalBytes : read.totalBytes();
                Optional<String> finalEtag = isDrive ? effectiveEtag : read.etag().or(() -> effectiveEtag);
                return new ProjectFileDownloadResult(
                        ProjectFileDownloadResult.Status.PARTIAL_206,
                        entity.getOriginalName(),
                        entity.getContentType(),
                        resolvedTotal,
                        range.length(),
                        Optional.of(range),
                        finalEtag,
                        effectiveLastModified,
                        read.body()
                );
            }

            InputStream stream;
            Optional<String> finalEtag = effectiveEtag;
            StorageReadResult read = readFromProviderOrNull(isDrive, entity, Optional.empty());
            if (read != null && read.body() != null) {
                stream = read.body();
                finalEtag = isDrive ? effectiveEtag : read.etag().or(() -> effectiveEtag);
                if (totalBytes < 0 && read.totalBytes() >= 0) {
                    totalBytes = read.totalBytes();
                }
            } else {
                stream = isDrive
                        ? googleDriveService.downloadFile(entity.getStorageKey())
                        : storageService.downloadFile(entity.getStorageBucket(), entity.getStorageKey());
            }

            return new ProjectFileDownloadResult(
                    ProjectFileDownloadResult.Status.FULL_200,
                    entity.getOriginalName(),
                    entity.getContentType(),
                    totalBytes,
                    totalBytes,
                    Optional.empty(),
                    finalEtag,
                    effectiveLastModified,
                    stream
            );
        } catch (IOException e) {
            log.error("Failed to download file from storage: {}", e.getMessage(), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Failed to download file from storage");
        }
    }

    private Optional<StorageObjectStat> fetchStatQuietly(boolean isDrive, ProjectFileEntity entity) {
        try {
            return isDrive
                    ? googleDriveService.statObject(entity.getStorageKey())
                    : storageService.statObject(entity.getStorageBucket(), entity.getStorageKey());
        } catch (IOException ex) {
            log.debug("Object stat unavailable for fileId={}: {}", entity.getId(), ex.getMessage());
            return Optional.empty();
        }
    }

    private StorageReadResult readFromProvider(boolean isDrive, ProjectFileEntity entity, Optional<ByteRange> range)
            throws IOException {
        StorageReadResult result = isDrive
                ? googleDriveService.readObject(entity.getStorageKey(), range)
                : storageService.readObject(new StorageReadRequest(entity.getStorageBucket(), entity.getStorageKey(), range));
        if (result == null || result.body() == null) {
            throw new IOException("Provider returned empty stream for ranged read");
        }
        return result;
    }

    private StorageReadResult readFromProviderOrNull(boolean isDrive, ProjectFileEntity entity, Optional<ByteRange> range)
            throws IOException {
        return isDrive
                ? googleDriveService.readObject(entity.getStorageKey(), range)
                : storageService.readObject(new StorageReadRequest(entity.getStorageBucket(), entity.getStorageKey(), range));
    }

    private static Optional<String> buildDeterministicStrongEtag(ProjectFileEntity entity) {
        if (entity.getId() == null) {
            return Optional.empty();
        }
        Instant stamp = entity.getUpdatedAt() != null ? entity.getUpdatedAt() : entity.getCreatedAt();
        long epochMillis = stamp != null ? stamp.toEpochMilli() : 0L;
        long size = entity.getFileSize() != null ? entity.getFileSize() : 0L;
        return Optional.of("\"tp-" + entity.getId() + "-" + size + "-" + epochMillis + "\"");
    }

    @Transactional
    public void deleteFile(Long projectId, Long fileId, Long userId) {
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

        StorageObjectLocator locator = StorageObjectLocator.of(entity.getStorageBucket(), entity.getStorageKey());
        String deletedId = entity.getId() != null ? String.valueOf(entity.getId()) : null;

        projectFileRepository.delete(entity);

        if (storageCleanupPort != null) {
            storageCleanupPort.enqueueCleanup(new StorageCleanupRequest(
                    locator,
                    "PROJECT_FILE",
                    deletedId,
                    projectId,
                    null
            ));
        }
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

    private record UploadedObject(String storageKey, String storageBucket, UUID operationId) {
    }

    private static final class S3UploadException extends RuntimeException {
        S3UploadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    private static final long MAX_GDRIVE_OPEN_RANGE_BYTES = 8L * 1024L * 1024L;
    private EgressS3StorageService egressS3StorageService;

    public static class RangeNotSatisfiableException extends BusinessException {
        private final long totalSize;

        public RangeNotSatisfiableException(long totalSize, String message) {
            super(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value(), message);
            this.totalSize = totalSize;
        }

        public long getTotalSize() {
            return totalSize;
        }
    }

    @Autowired(required = false)
    public void setEgressS3StorageService(EgressS3StorageService egressS3StorageService) {
        this.egressS3StorageService = egressS3StorageService;
    }

    @Transactional(readOnly = true)
    public FileStreamResource streamFile(Long projectId, Long fileId, Long userId, String rangeHeader) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectFileEntity entity = projectFileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "File not found"));

        String provider = resolveProvider(entity);
        if ("b2".equals(provider) && (egressS3StorageService == null || !egressS3StorageService.isConfigured())) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Backblaze B2 recording storage is not configured");
        }
        if ("gdrive".equals(provider) && (googleDriveService == null || !googleDriveService.isConfigured())) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Google Drive archival storage is not configured");
        }

        long totalSize = resolveFileSize(entity, provider);

        long start = 0L;
        long end = totalSize > 0 ? totalSize - 1 : 0L;
        boolean partial = false;

        if (rangeHeader != null && !rangeHeader.isBlank() && rangeHeader.trim().startsWith("bytes=") && totalSize > 0) {
            String spec = rangeHeader.trim().substring("bytes=".length());
            int commaIdx = spec.indexOf(',');
            if (commaIdx >= 0) {
                spec = spec.substring(0, commaIdx);
            }
            spec = spec.trim();
            int dashIdx = spec.indexOf('-');
            if (dashIdx < 0) {
                throw new RangeNotSatisfiableException(totalSize, "Invalid Range header");
            }
            try {
                String startStr = spec.substring(0, dashIdx).trim();
                String endStr = spec.substring(dashIdx + 1).trim();
                if (startStr.isEmpty()) {
                    long suffix = Long.parseLong(endStr);
                    if (suffix <= 0) {
                        throw new RangeNotSatisfiableException(totalSize, "Invalid suffix range");
                    }
                    start = Math.max(0L, totalSize - suffix);
                    end = totalSize - 1;
                } else {
                    start = Long.parseLong(startStr);
                    if (endStr.isEmpty()) {
                        long maxEnd = "gdrive".equals(provider)
                                ? Math.min(totalSize - 1, start + MAX_GDRIVE_OPEN_RANGE_BYTES - 1)
                                : (totalSize - 1);
                        end = maxEnd;
                    } else {
                        end = Math.min(Long.parseLong(endStr), totalSize - 1);
                    }
                }
            } catch (NumberFormatException e) {
                throw new RangeNotSatisfiableException(totalSize, "Invalid Range bounds");
            }

            if (start < 0 || start >= totalSize || end < start) {
                throw new RangeNotSatisfiableException(totalSize,
                        "Requested range not satisfiable for totalSize=" + totalSize);
            }
            partial = true;
        }

        final long finalStart = start;
        final long finalEnd = end;
        final boolean isPartial = partial;
        final String key = entity.getStorageKey();
        final String bucket = entity.getStorageBucket();

        FileStreamResource.StreamOpener opener = () -> {
            if ("b2".equals(provider) && egressS3StorageService != null) {
                return isPartial
                        ? egressS3StorageService.downloadFileRange(key, finalStart, finalEnd)
                        : egressS3StorageService.downloadFile(key);
            }
            if ("gdrive".equals(provider) && googleDriveService != null) {
                return isPartial
                        ? googleDriveService.downloadFileRange(key, finalStart, finalEnd)
                        : googleDriveService.downloadFile(key);
            }
            return isPartial
                    ? storageService.downloadFileRange(bucket, key, finalStart, finalEnd)
                    : storageService.downloadFile(bucket, key);
        };

        return new FileStreamResource(
                entity.getOriginalName(),
                entity.getContentType(),
                totalSize,
                finalStart,
                finalEnd,
                isPartial,
                opener
        );
    }

    private long resolveFileSize(ProjectFileEntity entity, String provider) {
        if (entity.getFileSize() != null && entity.getFileSize() > 0) {
            return entity.getFileSize();
        }
        try {
            if ("b2".equals(provider) && egressS3StorageService != null && egressS3StorageService.isConfigured()) {
                return egressS3StorageService.headObject(entity.getStorageKey()).contentLength();
            }
            if ("gdrive".equals(provider) && googleDriveService != null && googleDriveService.isConfigured()) {
                var meta = googleDriveService.getFileMetadata(entity.getStorageKey());
                return meta != null && meta.getSize() != null ? meta.getSize() : 0L;
            }
        } catch (Exception e) {
            log.warn("Could not resolve file size for fileId={}: {}", entity.getId(), e.getMessage());
        }
        return 0L;
    }

    private static String resolveProvider(ProjectFileEntity entity) {
        if (entity.getStorageProvider() != null && !entity.getStorageProvider().isBlank()) {
            return entity.getStorageProvider().toLowerCase();
        }
        return "googledrive".equalsIgnoreCase(entity.getStorageBucket()) ? "gdrive" : "s3";
    }
}
