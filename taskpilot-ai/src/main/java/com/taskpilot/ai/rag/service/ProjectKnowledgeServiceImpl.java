package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectKnowledgeServiceImpl implements ProjectKnowledgeService {

    private final ProjectMemberPort projectMemberPort;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingGateway embeddingGateway;

    @Override
    public List<ScoredChunk> searchKnowledge(Long projectId, Long userId, String query, int limit, double minScore) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }

        // Strict Tenant Isolation Gate: Must check membership BEFORE generating embedding or querying vectors
        boolean isMember = projectMemberPort.isProjectMember(projectId, userId);
        if (!isMember) {
            log.warn("Unauthorized knowledge access attempt: userId={} is not a member of projectId={}",
                    userId, projectId);
            throw new AccessDeniedException("User " + userId + " is not authorized to access knowledge for project " + projectId);
        }

        int fetchLimit = limit > 0 ? limit : 5;
        float[] queryVector = embeddingGateway.embedForSearch(query.trim());

        return documentChunkRepository.findNearestChunks(projectId, queryVector, fetchLimit, minScore);
    }

    @Override
    public String getKnowledgeContext(Long projectId, Long userId, String query, int maxChunks) {
        int limit = maxChunks > 0 ? maxChunks : 5;
        // Default threshold of 0.40 to keep only semantically relevant chunks
        List<ScoredChunk> chunks = searchKnowledge(projectId, userId, query, limit, 0.40);

        if (chunks.isEmpty()) {
            return "No relevant project documents found for query.";
        }

        StringBuilder sb = new StringBuilder("Relevant project documentation:\n");
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append(String.format("[%d] (Score: %.2f) %s\n\n", i + 1, c.similarity(), c.content()));
        }
        return sb.toString().trim();
    }
}
