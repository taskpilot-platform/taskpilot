package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.dto.DocumentResponse;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.storage.ProjectStorageInventoryContributor;
import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.contracts.storage.StorageTargetDomain;
import com.taskpilot.contracts.storage.StorageUploadOperationDto;
import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.contracts.storage.StorageUploadReservationCommand;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class ProjectDocumentServiceImpl implements ProjectDocumentService, ProjectStorageInventoryContributor {

    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024; // 25 MB
    private static final String RAG_BUCKET = "documents";
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "docx", "doc", "txt", "md", "markdown", "csv", "json", "html", "htm", "xml", "rtf"
    );

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentIngestionService documentIngestionService;
    private final ProjectKnowledgeService projectKnowledgeService;
    private final StorageService storageService;
    private final ProjectMemberPort projectMemberPort;
    private final StorageUploadOperationPort uploadOperationPort;
    private final TransactionTemplate transactionTemplate;

    public ProjectDocumentServiceImpl(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            DocumentIngestionService documentIngestionService,
            ProjectKnowledgeService projectKnowledgeService,
            StorageService storageService,
            ProjectMemberPort projectMemberPort) {
        this(documentRepository, documentChunkRepository, documentIngestionService,
                projectKnowledgeService, storageService, projectMemberPort, null, null);
    }

    @Autowired
    public ProjectDocumentServiceImpl(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            DocumentIngestionService documentIngestionService,
            ProjectKnowledgeService projectKnowledgeService,
            StorageService storageService,
            ProjectMemberPort projectMemberPort,
            @Autowired(required = false) StorageUploadOperationPort uploadOperationPort,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.documentIngestionService = documentIngestionService;
        this.projectKnowledgeService = projectKnowledgeService;
        this.storageService = storageService;
        this.projectMemberPort = projectMemberPort;
        this.uploadOperationPort = uploadOperationPort;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    @Override
    public String sourceType() {
        return "RAG_DOCUMENT";
    }

    @Override
    @Transactional(readOnly = true)
    public List<StorageObjectLocator> listProjectOwnedObjects(long projectId) {
        return documentRepository.findByProjectId(projectId).stream()
                .filter(doc -> doc.getStorageKey() != null && !doc.getStorageKey().isBlank())
                .map(doc -> StorageObjectLocator.of(RAG_BUCKET, doc.getStorageKey()))
                .toList();
    }

    @Override
    public DocumentResponse uploadDocument(Long projectId, MultipartFile file, Long userId) {
        validateActiveProjectManager(projectId, userId);
        validateFile(file);

        String originalFilename = file.getOriginalFilename();
        String safeFilename = originalFilename != null ? originalFilename : "unknown_file";
        String folder = "projects/" + projectId + "/documents";

        if (uploadOperationPort == null) {
            String storageKey;
            try {
                storageKey = storageService.uploadFile(file, folder, RAG_BUCKET);
            } catch (IOException e) {
                log.error("Failed to upload file to storage for project {}: {}", projectId, e.getMessage(), e);
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "File storage upload failed: " + e.getMessage());
            }
            DocumentEntity saved = persistQueuedDocument(projectId, file, safeFilename, storageKey, userId);
            return toResponse(saved);
        }

        String plannedKey = storageService.planObjectKey(folder, safeFilename);
        if (plannedKey == null || plannedKey.isBlank()) {
            plannedKey = folder + "/" + UUID.randomUUID() + "-" + safeFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        }

        StorageUploadOperationDto reservation = uploadOperationPort.reserve(new StorageUploadReservationCommand(
                userId,
                projectId,
                StorageTargetDomain.RAG_DOCUMENT,
                StorageProviderKind.S3,
                RAG_BUCKET,
                plannedKey,
                safeFilename,
                file.getContentType(),
                file.getSize()
        ));

        String resolvedKey;
        try {
            String uploadedKey = storageService.uploadFileAtKey(file, RAG_BUCKET, plannedKey);
            resolvedKey = (uploadedKey != null && !uploadedKey.isBlank()) ? uploadedKey : plannedKey;
            uploadOperationPort.markProviderWritten(reservation.operationId(), resolvedKey);
        } catch (IOException | RuntimeException e) {
            uploadOperationPort.compensateFailedUpload(
                    reservation.operationId(),
                    StorageObjectLocator.of(RAG_BUCKET, plannedKey),
                    "S3_UPLOAD_FAILED",
                    e.getMessage()
            );
            log.error("Failed to upload RAG document to storage for project {}: {}", projectId, e.getMessage(), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "File storage upload failed: " + e.getMessage());
        }

        DocumentEntity saved;
        try {
            saved = attachUploadedDocument(projectId, file, safeFilename, resolvedKey, userId, reservation.operationId());
        } catch (RuntimeException ex) {
            uploadOperationPort.compensateFailedUpload(
                    reservation.operationId(),
                    StorageObjectLocator.of(RAG_BUCKET, resolvedKey),
                    "RAG_METADATA_ATTACH_FAILED",
                    ex.getMessage()
            );
            throw ex;
        }

        log.info("Document registered and queued id={}, project={}, file={}", saved.getId(), projectId, saved.getOriginalFilename());
        return toResponse(saved);
    }

    private DocumentEntity attachUploadedDocument(
            Long projectId,
            MultipartFile file,
            String safeFilename,
            String resolvedKey,
            Long userId,
            UUID operationId
    ) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status ->
                    doAttachDocumentInTx(projectId, file, safeFilename, resolvedKey, userId, operationId));
        }
        return doAttachDocumentInTx(projectId, file, safeFilename, resolvedKey, userId, operationId);
    }

    private DocumentEntity doAttachDocumentInTx(
            Long projectId,
            MultipartFile file,
            String safeFilename,
            String resolvedKey,
            Long userId,
            UUID operationId
    ) {
        projectMemberPort.lockProjectForStorageWrite(projectId);
        validateActiveProjectManager(projectId, userId);

        if (uploadOperationPort != null) {
            uploadOperationPort.registerRollbackCompensation(
                    operationId,
                    StorageObjectLocator.of(RAG_BUCKET, resolvedKey),
                    "RAG_METADATA_ROLLBACK",
                    "RAG document attach transaction rolled back"
            );
        }

        DocumentEntity saved = persistQueuedDocument(projectId, file, safeFilename, resolvedKey, userId);
        if (uploadOperationPort != null && operationId != null) {
            uploadOperationPort.markAttachedInCurrentTransaction(operationId, saved.getId());
        }
        return saved;
    }

    private DocumentEntity persistQueuedDocument(
            Long projectId,
            MultipartFile file,
            String safeFilename,
            String storageKey,
            Long userId
    ) {
        DocumentEntity document = DocumentEntity.builder()
                .projectId(projectId)
                .storageKey(storageKey)
                .originalFilename(safeFilename)
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .status(DocumentStatus.QUEUED)
                .createdBy(userId)
                .build();
        DocumentEntity saved = documentRepository.save(document);
        if (uploadOperationPort != null) {
            documentRepository.flush();
        }
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponse> getProjectDocuments(Long projectId, Long userId) {
        validateProjectMembership(projectId, userId);
        return documentRepository.findByProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getDocument(Long projectId, Long documentId, Long userId) {
        validateProjectMembership(projectId, userId);
        DocumentEntity document = documentRepository.findByIdAndProjectId(documentId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Document not found: " + documentId));
        return toResponse(document);
    }

    @Override
    @Transactional
    public void deleteDocument(Long projectId, Long documentId, Long userId) {
        projectMemberPort.lockProjectForStorageWrite(projectId);
        validateActiveProjectManager(projectId, userId);
        DocumentEntity document = documentRepository.findByIdAndProjectId(documentId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Document not found: " + documentId));

        documentIngestionService.deleteDocument(document.getId());
        log.info("Document id={} deleted from project={}", documentId, projectId);
    }

    @Override
    @Transactional
    public DocumentResponse retryIngestion(Long projectId, Long documentId, Long userId) {
        projectMemberPort.lockProjectForStorageWrite(projectId);
        validateActiveProjectManager(projectId, userId);
        DocumentEntity document = documentRepository.findByIdAndProjectId(documentId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Document not found: " + documentId));

        document.setStatus(DocumentStatus.QUEUED);
        document.setRetryCount(0);
        document.setNextAttemptAt(null);
        document.setLeaseUntil(null);
        document.setErrorMessage(null);
        DocumentEntity updated = documentRepository.save(document);

        log.info("Re-ingestion queued for document id={} in project={}", documentId, projectId);

        return toResponse(updated);
    }

    @Override
    public List<ScoredChunk> searchDocuments(Long projectId, String query, int limit, double minScore, Long userId) {
        validateProjectMembership(projectId, userId);
        return projectKnowledgeService.searchKnowledge(projectId, userId, query, limit, minScore);
    }

    private void validateActiveProjectManager(Long projectId, Long userId) {
        validateProjectManager(projectId, userId);
        if (projectMemberPort.isProjectArchived(projectId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }
    }

    private void validateProjectManager(Long projectId, Long userId) {
        validateProjectMembership(projectId, userId);
        if (!projectMemberPort.isProjectManager(projectId, userId)) {
            log.warn("Manager action rejected: user {} is not MANAGER of project {}", userId, projectId);
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Only Project Manager can perform this action");
        }
    }

    private void validateProjectMembership(Long projectId, Long userId) {
        if (projectId == null || userId == null || !projectMemberPort.isProjectMember(projectId, userId)) {
            log.warn("Access denied to project {} for user {}", projectId, userId);
            throw new AccessDeniedException("User is not authorized to access project knowledge: " + projectId);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "File must not be empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "File size exceeds 25MB limit");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "File must have a valid extension");
        }

        String extension = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Unsupported file extension: " + extension + ". Allowed: " + ALLOWED_EXTENSIONS);
        }
    }

    private DocumentResponse toResponse(DocumentEntity doc) {
        long chunkCount = documentChunkRepository.countByDocumentId(doc.getId());
        return DocumentResponse.builder()
                .id(doc.getId())
                .projectId(doc.getProjectId())
                .originalFilename(doc.getOriginalFilename())
                .contentType(doc.getContentType())
                .fileSize(doc.getFileSize())
                .status(doc.getStatus())
                .errorMessage(doc.getErrorMessage())
                .chunkCount(chunkCount)
                .createdBy(doc.getCreatedBy())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }
}
