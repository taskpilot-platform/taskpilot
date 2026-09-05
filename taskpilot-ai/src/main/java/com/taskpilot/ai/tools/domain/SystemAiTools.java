package com.taskpilot.ai.tools.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.contracts.aiquery.dto.SmartQueryRequestDto;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.service.SmartQueryService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.taskpilot.ai.tools.support.AiToolSupport.*;

@Slf4j
@Component
public class SystemAiTools {

    private final PendingAiActionService pendingAiActionService;
    private final SmartQueryService smartQueryService;
    @Nullable
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public SystemAiTools(
            PendingAiActionService pendingAiActionService,
            SmartQueryService smartQueryService,
            @Nullable JdbcTemplate jdbcTemplate) {
        this.pendingAiActionService = pendingAiActionService;
        this.smartQueryService = smartQueryService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public SystemAiTools(
            PendingAiActionService pendingAiActionService,
            SmartQueryService smartQueryService) {
        this(pendingAiActionService, smartQueryService, null);
    }

    @Tool("Confirm and execute a pending write action by its unique action ID.")
    public Object confirmPendingAction(@P("Pending action ID returned by a confirmationRequired tool result") String actionId) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        if (!hasText(actionId) && isCurrentUserConfirming()) {
            return pendingAiActionService.confirmLatest(userId, sessionId);
        }
        if (!isCurrentUserConfirming(actionId) && !isCurrentUserConfirming()) {
            return "Confirmation not accepted. Ask the user to confirm this exact action ID before executing: "
                    + actionId;
        }
        return pendingAiActionService.confirm(actionId, userId, sessionId);
    }


    @Tool("Confirm and execute the most recent pending write action in this session.")
    public Object confirmLatestPendingAction() {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        if (!isCurrentUserConfirming()) {
            return "Confirmation not accepted. Ask the user to clearly confirm before executing.";
        }
        return pendingAiActionService.confirmLatest(userId, sessionId);
    }


    @Tool("Cancel a pending write action by its unique action ID.")
    public Object cancelPendingAction(@P("Pending action ID to cancel") String actionId) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        pendingAiActionService.cancel(actionId, userId, sessionId);
        return Map.of("cancelled", true, "actionId", actionId);
    }




    @Tool("Execute a read-only raw SQL SELECT query to retrieve complex, joined, or aggregated database information directly. " +
          "Use table names: 'projects', 'project_members', 'tasks', 'users', 'sprints', 'comments', 'labels', 'skills', 'user_skills', 'notifications'. " +
          "Only SELECT statement is allowed. Useful for fetching multiple tables' data in one step.")
    public Object executeQuerySql(@P("The SELECT SQL query statement to run") String sql) {
        log.info("[AiTool] executeQuerySql called with SQL: {}", sql);
        if (jdbcTemplate == null) {
            log.warn("[AiTool] jdbcTemplate is null, executeQuerySql is disabled in current context.");
            return Map.of("error", "Database query tool is not initialized in this environment.");
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL query cannot be null or empty.");
        }
        
        String cleanSql = sql.trim().toUpperCase();
        if (!cleanSql.startsWith("SELECT") && !cleanSql.startsWith("WITH")) {
            throw new IllegalArgumentException("Only read-only SELECT queries are allowed for security reasons.");
        }
        
        // Prevent simple SQL write attempts in comments or subqueries (naive check)
        List<String> forbidden = List.of("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "REPLACE");
        for (String word : forbidden) {
            if (cleanSql.contains(" " + word + " ") || cleanSql.contains("\n" + word + " ") || cleanSql.contains("\t" + word + " ")) {
                throw new IllegalArgumentException("Forbidden keyword '" + word + "' detected in SQL statement.");
            }
        }

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
            return Map.of("results", results, "totalMatched", results.size());
        } catch (Exception e) {
            log.error("[AiTool] executeQuerySql failed", e);
            return Map.of("error", e.getMessage());
        }
    }


    @Tool("Execute multiple query chains in parallel. Each chain is a sequence of dependent queries. " +
          "Chains run simultaneously on separate threads for maximum speed. " +
          "Entities: projects, tasks, members, sprints, comments, workload, notifications. " +
          "Use 'ref' within a chain to reference previous step results by key name. " +
          "Use 'aggregate' for special project selection: $latest, $mostMembers, $mostTasks. " +
          "CRITICAL: Do NOT call this tool if the request involves any write/CUD operations (e.g. createTask, patchTask, createProject). You MUST call the specific CUD tool directly in the first turn.")
    public Object smartQuery(
        @P("List of query chains. Each chain is a list of sequential query steps. " +
           "Example: " +
           "[{\"steps\": [{\"key\":\"p\", \"entity\":\"projects\", \"aggregate\":\"$latest\"}, " +
           "{\"key\":\"t\", \"entity\":\"tasks\", \"ref\":{\"projectId\":\"p\"}, \"filters\":{\"dueToday\":\"true\"}}]}, " +
           "{\"steps\": [{\"key\":\"all\", \"entity\":\"projects\"}, " +
           "{\"key\":\"w\", \"entity\":\"workload\", \"ref\":{\"projectId\":\"all\"}, \"sort\":\"activeWorkloadScore DESC\", \"limit\":1}]}]") 
        List<java.util.Map> chains
    ) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] smartQuery called by user {} with chains raw: {}", userId, chains);
        
        List<SmartQueryRequestDto.QueryChain> parsedChains = normalizeAndParseChains(chains);
        SmartQueryRequestDto request = new SmartQueryRequestDto(parsedChains);
        validateRequest(request);
        return smartQueryService.execute(request, userId);
    }

    @SuppressWarnings("unchecked")
    private List<SmartQueryRequestDto.QueryChain> normalizeAndParseChains(List<java.util.Map> chainsRaw) {
        if (chainsRaw == null) {
            return List.of();
        }
        for (java.util.Map chain : chainsRaw) {
            if (chain == null) continue;
            Object stepsObj = chain.get("steps");
            if (stepsObj instanceof List<?> stepsList) {
                for (Object stepObj : stepsList) {
                    if (stepObj instanceof java.util.Map<?, ?> stepMap) {
                        java.util.Map<String, Object> typedStepMap = (java.util.Map<String, Object>) stepMap;
                        normalizeMapValuesToString(typedStepMap, "filters");
                        normalizeMapValuesToString(typedStepMap, "ref");
                    }
                }
            }
        }
        return PATCH_OBJECT_MAPPER.convertValue(chainsRaw, new com.fasterxml.jackson.core.type.TypeReference<List<SmartQueryRequestDto.QueryChain>>() {});
    }

    @SuppressWarnings("unchecked")
    private void normalizeMapValuesToString(java.util.Map<String, Object> stepMap, String key) {
        Object valObj = stepMap.get(key);
        if (valObj instanceof java.util.Map<?, ?> valMap) {
            java.util.Map<String, Object> typedMap = (java.util.Map<String, Object>) valMap;
            java.util.Map<String, String> stringMap = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, Object> entry : typedMap.entrySet()) {
                Object entryVal = entry.getValue();
                stringMap.put(entry.getKey(), entryVal != null ? String.valueOf(entryVal) : null);
            }
            stepMap.put(key, stringMap);
        }
    }
    
    private void validateRequest(SmartQueryRequestDto request) {
        if (request == null || request.chains() == null) {
            throw new IllegalArgumentException("Request chains cannot be null");
        }
        if (request.chains().size() > 4) {
            throw new IllegalArgumentException("Maximum of 4 parallel chains is allowed");
        }
        int totalSteps = 0;
        for (var chain : request.chains()) {
            if (chain == null || chain.steps() == null) continue;
            if (chain.steps().size() > 5) {
                throw new IllegalArgumentException("Maximum of 5 steps per chain is allowed");
            }
            totalSteps += chain.steps().size();
        }
        if (totalSteps > 15) {
            throw new IllegalArgumentException("Maximum of 15 total steps across all chains is allowed");
        }
    }
}
