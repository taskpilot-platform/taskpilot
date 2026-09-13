package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.repository.DocumentChunkRepository;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ProjectKnowledgeServiceImpl implements ProjectKnowledgeService {

    private final ProjectMemberPort projectMemberPort;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingGateway embeddingGateway;
    private final DocumentDiversityContextSelector diversityContextSelector;

    @Autowired
    public ProjectKnowledgeServiceImpl(
            ProjectMemberPort projectMemberPort,
            DocumentChunkRepository documentChunkRepository,
            EmbeddingGateway embeddingGateway,
            DocumentDiversityContextSelector diversityContextSelector) {
        this.projectMemberPort = projectMemberPort;
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingGateway = embeddingGateway;
        this.diversityContextSelector = diversityContextSelector;
    }

    public ProjectKnowledgeServiceImpl(
            ProjectMemberPort projectMemberPort,
            DocumentChunkRepository documentChunkRepository,
            EmbeddingGateway embeddingGateway) {
        this(projectMemberPort, documentChunkRepository, embeddingGateway, new DocumentDiversityContextSelector());
    }

    @Override
    public List<ScoredChunk> searchKnowledge(Long projectId, Long userId, String query, int limit, double minScore) {
        return searchKnowledge(projectId, null, userId, query, limit, minScore);
    }

    @Override
    public List<ScoredChunk> searchKnowledge(Long projectId, Long documentId, Long userId, String query, int limit, double minScore) {
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
        float[] queryVector;
        try {
            queryVector = embeddingGateway.embedForSearch(query.trim());
            logSearchDetails(query.trim(), queryVector, true);
        } catch (Exception e) {
            logSearchDetails(query.trim(), null, false);
            throw e;
        }

        List<ScoredChunk> results;
        if (documentId != null) {
            results = documentChunkRepository.findByDocumentAndNearest(projectId, documentId, queryVector, fetchLimit, minScore);
        } else {
            results = documentChunkRepository.findByProjectAndNearest(projectId, queryVector, fetchLimit, minScore);
        }

        logFinalResultsAndDtoMapping(results);
        return results;
    }

    @Override
    public List<ScoredChunk> getContextChunks(Long projectId, Long userId, String query, int maxChunks) {
        return getContextChunks(projectId, null, userId, query, maxChunks);
    }

    @Override
    public List<ScoredChunk> getContextChunks(Long projectId, Long documentId, Long userId, String query, int maxChunks) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }

        boolean isMember = projectMemberPort.isProjectMember(projectId, userId);
        if (!isMember) {
            log.warn("Unauthorized knowledge access attempt: userId={} is not a member of projectId={}",
                    userId, projectId);
            throw new AccessDeniedException("User " + userId + " is not authorized to access knowledge for project " + projectId);
        }

        int finalContextLimit = maxChunks > 0 ? maxChunks : 6;
        float[] queryVector;
        try {
            queryVector = embeddingGateway.embedForSearch(query.trim());
            logSearchDetails(query.trim(), queryVector, true);
        } catch (Exception e) {
            logSearchDetails(query.trim(), null, false);
            throw e;
        }

        if (documentId != null) {
            // Document-focused path: top candidates (default 6), NO diversity cap
            log.debug("[RAG Retrieval] Document-focused retrieval: projectId={}, documentId={}, candidateLimit={}",
                    projectId, documentId, finalContextLimit);
            List<ScoredChunk> docResults = documentChunkRepository.findByDocumentAndNearest(projectId, documentId, queryVector, finalContextLimit, 0.40);
            logContextRetrievalAndCandidates(query.trim(), finalContextLimit, 0.40, docResults);
            logAfterDiversityAndBudget(docResults, finalContextLimit);
            logFinalResultsAndDtoMapping(docResults);
            return docResults;
        } else {
            // Project-wide path: candidate pool of 20, two-pass diversity soft-cap (max 2 per doc, up to 6 final context)
            List<ScoredChunk> candidates = documentChunkRepository.findByProjectAndNearest(projectId, queryVector, 20, 0.40);
            logContextRetrievalAndCandidates(query.trim(), 20, 0.40, candidates);

            List<ScoredChunk> selected = diversityContextSelector.selectProjectContext(candidates, finalContextLimit, 2);
            log.debug("[RAG FILTER]\nstage=diversity_selection\nbefore={}\nafter={}\nremoved={}\nreason=maxPerDocument cap (2) / maxContext limit ({})",
                    candidates.size(), selected.size(), candidates.size() - selected.size(), finalContextLimit);
            log.debug("[RAG Retrieval] Project-wide diversified context: candidates={}, finalContext={}, selectedDocs={}",
                    candidates.size(), selected.size(),
                    selected.stream().map(ScoredChunk::documentId).toList());

            logAfterDiversityAndBudget(selected, finalContextLimit);
            logFinalResultsAndDtoMapping(selected);
            return selected;
        }
    }

    private void logContextRetrievalAndCandidates(String query, int candidateLimit, double minScore, List<ScoredChunk> candidates) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("\n[RAG CONTEXT RETRIEVAL]\nquery=\"{}\"\ncandidateLimit={}\nsimilarityThreshold={}\nrawCandidates={}",
                query, candidateLimit, minScore, candidates.size());

        StringBuilder sb = new StringBuilder("\n[RAG CANDIDATES BEFORE DIVERSITY]\n");
        Map<String, Integer> sourceDist = new java.util.LinkedHashMap<>();
        int limit = Math.min(20, candidates.size());
        for (int i = 0; i < limit; i++) {
            ScoredChunk c = candidates.get(i);
            String docName = c.documentName() != null ? c.documentName() : "Doc #" + c.documentId();
            sourceDist.put(docName, sourceDist.getOrDefault(docName, 0) + 1);
            String preview = sanitizePreview(c.content(), 200);
            sb.append(String.format("\n#%d document=\"%s\" chunkId=%d documentId=%d chunkIndex=%d similarity=%.4f\n    preview: \"%s...\"",
                    i + 1, docName, c.chunkId(), c.documentId(), c.chunkIndex(), c.similarity(), preview));
        }
        log.debug("{}", sb.toString());

        StringBuilder distSb = new StringBuilder("\n[RAG SOURCE DISTRIBUTION]");
        for (Map.Entry<String, Integer> entry : sourceDist.entrySet()) {
            distSb.append(String.format("\n%s = %d", entry.getKey(), entry.getValue()));
        }
        log.debug("{}", distSb.toString());
    }

    private void logAfterDiversityAndBudget(List<ScoredChunk> selected, int maxContext) {
        if (!log.isDebugEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder("\n[RAG AFTER DIVERSITY]\n");
        Map<String, Integer> sourceDist = new java.util.LinkedHashMap<>();
        int totalChars = 0;
        for (int i = 0; i < selected.size(); i++) {
            ScoredChunk c = selected.get(i);
            String docName = c.documentName() != null ? c.documentName() : "Doc #" + c.documentId();
            sourceDist.put(docName, sourceDist.getOrDefault(docName, 0) + 1);
            if (c.content() != null) {
                totalChars += c.content().length();
            }
            String preview = sanitizePreview(c.content(), 200);
            sb.append(String.format("\n#%d document=\"%s\" chunkId=%d documentId=%d chunkIndex=%d similarity=%.4f\n    preview: \"%s...\"",
                    i + 1, docName, c.chunkId(), c.documentId(), c.chunkIndex(), c.similarity(), preview));
        }
        sb.append(String.format("\n\nFinal count=%d", selected.size()));
        log.debug("{}", sb.toString());

        StringBuilder distSb = new StringBuilder("\n[RAG SOURCE DISTRIBUTION AFTER DIVERSITY]");
        for (Map.Entry<String, Integer> entry : sourceDist.entrySet()) {
            distSb.append(String.format("\n%s = %d", entry.getKey(), entry.getValue()));
        }
        log.debug("{}", distSb.toString());

        int estimatedTokens = (int) Math.round(totalChars / 4.0);
        log.debug("\n[RAG CONTEXT BUDGET]\nmaxChunks={}\nmaxTokens=none (no hard token budget limit on chunks)\nmaxChars=unlimited (actual selected chars: {})\nestimatedFinalTokens={}",
                maxContext, totalChars, estimatedTokens);
    }

    private String sanitizePreview(String content, int maxLen) {
        if (content == null) return "";
        String singleLine = content.replaceAll("[\\r\\n\\t]+", " ").trim();
        return singleLine.length() <= maxLen ? singleLine : singleLine.substring(0, maxLen);
    }

    private void logSearchDetails(String query, float[] queryVector, boolean success) {
        if (!log.isDebugEnabled()) {
            return;
        }
        int estimatedTokens = Math.max(1, query.trim().split("\\s+").length);
        String provider = "Google AI (Gemini)";
        String model = "gemini-embedding-2";
        int dimension = queryVector != null ? queryVector.length : 768;
        log.debug("\n[RAG SEARCH]\nquery={}\nprovider={}\nmodel={}\nembeddingDimension={}\nestimatedTokens={}\nembeddingRequestSuccess={}",
                query, provider, model, dimension, estimatedTokens, success);
    }

    private void logFinalResultsAndDtoMapping(List<ScoredChunk> results) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("[RAG] No reranker/post-ranking applied");

        StringBuilder finalSb = new StringBuilder("[FINAL RESULTS]");
        for (int i = 0; i < results.size(); i++) {
            ScoredChunk c = results.get(i);
            finalSb.append(String.format("\n#%d chunkId=%d, docId=%d, fileName=\"%s\", sim=%.4f",
                    i + 1, c.chunkId(), c.documentId(), c.documentName() != null ? c.documentName() : "Doc #" + c.documentId(), c.similarity()));
        }
        log.debug("{}", finalSb.toString());

        StringBuilder dtoSb = new StringBuilder("[RAG DTO MAPPING]\nraw similarity/distance -> mapped relevance -> final rank:");
        for (int i = 0; i < results.size(); i++) {
            ScoredChunk c = results.get(i);
            double dist = 1.0 - c.similarity();
            double relevancePercent = c.similarity() * 100.0;
            dtoSb.append(String.format("\n#%d: raw sim=%.4f, dist=%.4f -> mapped relevance=%.1f%% -> final rank=#%d",
                    i + 1, c.similarity(), dist, relevancePercent, i + 1));
        }
        log.debug("{}", dtoSb.toString());
    }

    @Override
    public String getKnowledgeContext(Long projectId, Long userId, String query, int maxChunks) {
        return getKnowledgeContext(projectId, null, userId, query, maxChunks);
    }

    @Override
    public String getKnowledgeContext(Long projectId, Long documentId, Long userId, String query, int maxChunks) {
        List<ScoredChunk> chunks = getContextChunks(projectId, documentId, userId, query, maxChunks);

        if (chunks.isEmpty()) {
            return "No relevant project documents found for query.";
        }

        StringBuilder sb = new StringBuilder("Relevant project documentation:\n");
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            String docLabel = c.documentName() != null ? c.documentName() : "Doc #" + c.documentId();
            sb.append(String.format("[%d] (%s, Score: %.2f) %s\n\n", i + 1, docLabel, c.similarity(), c.content()));
        }
        return sb.toString().trim();
    }
}
