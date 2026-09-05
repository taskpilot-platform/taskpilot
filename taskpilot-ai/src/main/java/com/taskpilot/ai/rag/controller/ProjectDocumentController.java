package com.taskpilot.ai.rag.controller;

import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.dto.DocumentResponse;
import com.taskpilot.ai.rag.service.ProjectDocumentService;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/documents")
@RequiredArgsConstructor
@Tag(name = "08. Project Documents (RAG)", description = "APIs for project document management, knowledge indexing, and RAG retrieval")
public class ProjectDocumentController {

    private final ProjectDocumentService projectDocumentService;
    private final UserIdentityPort userIdentityPort;

    @Operation(summary = "Upload and ingest project document", description = "Upload a document to S3 and automatically trigger background RAG chunking and vector indexing")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DocumentResponse> uploadDocument(
            @PathVariable Long projectId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        log.info("Upload document request for project {} from user {}", projectId, userId);
        DocumentResponse response = projectDocumentService.uploadDocument(projectId, file, userId);
        return ApiResponse.created("Document uploaded and indexing started", response);
    }

    @Operation(summary = "List project documents", description = "Get list of all documents uploaded for the project with their processing status and chunk count")
    @GetMapping
    public ApiResponse<List<DocumentResponse>> getDocuments(
            @PathVariable Long projectId,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        List<DocumentResponse> documents = projectDocumentService.getProjectDocuments(projectId, userId);
        return ApiResponse.ok("Documents retrieved successfully", documents);
    }

    @Operation(summary = "Get document detail", description = "Get status and details of a specific document")
    @GetMapping("/{documentId}")
    public ApiResponse<DocumentResponse> getDocument(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        DocumentResponse response = projectDocumentService.getDocument(projectId, documentId, userId);
        return ApiResponse.ok("Document retrieved successfully", response);
    }

    @Operation(summary = "Delete document", description = "Delete document record, S3 file, and all associated pgvector chunks")
    @DeleteMapping("/{documentId}")
    public ApiResponse<Void> deleteDocument(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        log.info("Delete document {} in project {} by user {}", documentId, projectId, userId);
        projectDocumentService.deleteDocument(projectId, documentId, userId);
        return ApiResponse.ok("Document deleted successfully", null);
    }

    @Operation(summary = "Retry document ingestion", description = "Retry or re-index a document that previously failed or needs re-embedding")
    @PostMapping("/{documentId}/retry")
    public ApiResponse<DocumentResponse> retryIngestion(
            @PathVariable Long projectId,
            @PathVariable Long documentId,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        log.info("Retry ingestion for document {} in project {} by user {}", documentId, projectId, userId);
        DocumentResponse response = projectDocumentService.retryIngestion(projectId, documentId, userId);
        return ApiResponse.ok("Document re-ingestion started", response);
    }

    @Operation(summary = "Search project knowledge", description = "Direct semantic search against project knowledge base using pgvector cosine similarity")
    @GetMapping("/search")
    public ApiResponse<List<ScoredChunk>> searchKnowledge(
            @PathVariable Long projectId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "0.4") double minScore,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        List<ScoredChunk> results = projectDocumentService.searchDocuments(projectId, query, limit, minScore, userId);
        return ApiResponse.ok("Knowledge search completed", results);
    }

    private Long resolveUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        return userIdentityPort.findByEmail(authentication.getName())
                .map(identity -> identity.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }
}
