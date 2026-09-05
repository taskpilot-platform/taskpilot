package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.dto.DocumentResponse;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.ai.rag.repository.DocumentRepository;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectDocumentServiceImpl implements ProjectDocumentService {

    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024; // 25 MB
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "docx", "doc", "txt", "md", "markdown", "csv", "json", "html", "htm", "xml", "rtf"
    );

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentIngestionService documentIngestionService;
    private final ProjectKnowledgeService projectKnowledgeService;
    private final StorageService storageService;
    private final ProjectMemberPort projectMemberPort;

    @Override
    @Transactional
    public DocumentResponse uploadDocument(Long projectId, MultipartFile file, Long userId) {
        validateProjectMembership(projectId, userId);
        validateFile(file);

        String originalFilename = file.getOriginalFilename();
        String folder = "projects/" + projectId + "/documents";

        String storageKey;
        try {
            storageKey = storageService.uploadFile(file, folder);
        } catch (IOException e) {
            log.error("Failed to upload file to storage for project {}: {}", projectId, e.getMessage(), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "File storage upload failed: " + e.getMessage());
        }

        DocumentEntity document = DocumentEntity.builder()
                .projectId(projectId)
                .storageKey(storageKey)
                .originalFilename(originalFilename != null ? originalFilename : "unknown_file")
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .status(DocumentStatus.UPLOADING)
                .createdBy(userId)
                .build();

        DocumentEntity saved = documentRepository.save(document);
        log.info("Document registered id={}, project={}, file={}", saved.getId(), projectId, saved.getOriginalFilename());

        // Asynchronously trigger ingestion pipeline
        documentIngestionService.ingestDocumentAsync(saved.getId());

        return toResponse(saved);
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
        validateProjectMembership(projectId, userId);
        DocumentEntity document = documentRepository.findByIdAndProjectId(documentId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Document not found: " + documentId));

        documentIngestionService.deleteDocument(document.getId());
        log.info("Document id={} deleted from project={}", documentId, projectId);
    }

    @Override
    @Transactional
    public DocumentResponse retryIngestion(Long projectId, Long documentId, Long userId) {
        validateProjectMembership(projectId, userId);
        DocumentEntity document = documentRepository.findByIdAndProjectId(documentId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Document not found: " + documentId));

        document.setStatus(DocumentStatus.PROCESSING);
        document.setErrorMessage(null);
        DocumentEntity updated = documentRepository.save(document);

        documentIngestionService.ingestDocumentAsync(documentId);
        log.info("Re-ingestion triggered for document id={} in project={}", documentId, projectId);

        return toResponse(updated);
    }

    @Override
    public List<ScoredChunk> searchDocuments(Long projectId, String query, int limit, double minScore, Long userId) {
        validateProjectMembership(projectId, userId);
        return projectKnowledgeService.searchKnowledge(projectId, userId, query, limit, minScore);
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
