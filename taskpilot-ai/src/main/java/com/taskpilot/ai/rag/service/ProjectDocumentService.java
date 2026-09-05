package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.dto.DocumentResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ProjectDocumentService {

    DocumentResponse uploadDocument(Long projectId, MultipartFile file, Long userId);

    List<DocumentResponse> getProjectDocuments(Long projectId, Long userId);

    DocumentResponse getDocument(Long projectId, Long documentId, Long userId);

    void deleteDocument(Long projectId, Long documentId, Long userId);

    DocumentResponse retryIngestion(Long projectId, Long documentId, Long userId);

    List<ScoredChunk> searchDocuments(Long projectId, String query, int limit, double minScore, Long userId);
}
