package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.rag.domain.ScoredChunk;
import com.taskpilot.ai.rag.service.ProjectKnowledgeService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeAiTools {

    private final ProjectKnowledgeService projectKnowledgeService;

    @Tool("Search project knowledge base and uploaded documentation (RAG). Retrieves relevant semantic excerpts from project documents (specifications, requirements, architecture, guidelines, etc.). User must be a member of the project.")
    public Object searchProjectKnowledge(
            @P("The project ID to search documentation within") Long projectId,
            @P("The natural language search query or topic to search for in project documents") String query,
            @P("Optional. Maximum number of document excerpts to return (default 5, max 10)") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] searchProjectKnowledge called for user={}, projectId={}, query='{}', limit={}",
                userId, projectId, query, limit);

        if (projectId == null) {
            return "Project ID is required to search project knowledge.";
        }
        if (query == null || query.isBlank()) {
            return "Search query must not be empty.";
        }

        int maxResults = limit != null && limit > 0 ? Math.min(limit, 10) : 5;
        List<ScoredChunk> chunks = projectKnowledgeService.searchKnowledge(projectId, userId, query, maxResults, 0.40);

        if (chunks.isEmpty()) {
            return "No relevant project documents found for query: " + query;
        }

        return chunks.stream()
                .map(c -> Map.of(
                        "chunkIndex", c.chunkIndex(),
                        "similarity", Math.round(c.similarity() * 1000.0) / 1000.0,
                        "content", c.content()
                ))
                .toList();
    }
}
