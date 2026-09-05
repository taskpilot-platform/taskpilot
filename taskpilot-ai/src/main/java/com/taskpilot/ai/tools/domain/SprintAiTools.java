package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.aiquery.dto.SprintSummaryDto;
import com.taskpilot.contracts.aiquery.port.out.SprintQueryPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.type.TypeReference;

import static com.taskpilot.ai.tools.support.AiToolSupport.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SprintAiTools {

    private final SprintQueryPort sprintQueryPort;
    private final PendingAiActionService pendingAiActionService;

    @Tool("Fetch all sprints belonging to a project. Supports optional status filter.")
    public Object getSprintsByProject(
            @P("The ID of the project") String projectId,
            @P("Optional. Filter sprints by status (e.g. ACTIVE, PLANNING, COMPLETED)") String status,
            @P("Optional. Maximum number of results to return. Default 10, max 30.") Integer limit) {
        log.info("[AiTool] getSprintsByProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        List<com.taskpilot.contracts.aiquery.dto.SprintSummaryDto> allSprints = sprintQueryPort.getSprintsByProject(toLong(projectId), userId);

        List<com.taskpilot.contracts.aiquery.dto.SprintSummaryDto> filtered = allSprints.stream()
                .filter(s -> status == null || status.isBlank() || status.equalsIgnoreCase(s.status()))
                .limit(limit != null ? Math.max(1, Math.min(limit, 30)) : 10)
                .collect(Collectors.toList());

        return Map.of("results", filtered, "totalMatched", filtered.size());
    }


    @Tool("Fetch the sprint backlog of a project (unscheduled tasks and sprints).")
    public Object getSprintBacklog(
            @P("The ID of the project") Object projectId,
            @P("Optional. Maximum number of tasks to return per sprint/unscheduled. Default 10, max 30.") Integer limit) {
        Long resolvedProjectId = toLong(projectId);
        log.info("[AiTool] getSprintBacklog called for project {}", resolvedProjectId);
        Long userId = ToolExecutionContext.requireUserId();
        Object rawBacklog = sprintQueryPort.getSprintBacklog(resolvedProjectId, userId);
        try {
            Map<String, Object> backlogMap = PATCH_OBJECT_MAPPER.convertValue(rawBacklog, new TypeReference<Map<String, Object>>() {});
            if (backlogMap.containsKey("unscheduled")) {
                List<Map<String, Object>> unscheduled = (List<Map<String, Object>>) backlogMap.get("unscheduled");
                if (unscheduled != null) {
                    List<Map<String, Object>> filteredUnscheduled = unscheduled.stream()
                        .limit(limit != null ? Math.max(1, Math.min(limit, 30)) : 10)
                        .collect(Collectors.toList());
                    backlogMap.put("unscheduled", filteredUnscheduled);
                }
            }
            if (backlogMap.containsKey("sections")) {
                List<Map<String, Object>> sections = (List<Map<String, Object>>) backlogMap.get("sections");
                if (sections != null) {
                    List<Map<String, Object>> filteredSections = sections.stream()
                        .map(section -> {
                            Map<String, Object> newSec = new LinkedHashMap<>(section);
                            List<Map<String, Object>> tasks = (List<Map<String, Object>>) newSec.get("tasks");
                            if (tasks != null) {
                                List<Map<String, Object>> filteredTasks = tasks.stream()
                                    .limit(limit != null ? Math.max(1, Math.min(limit, 30)) : 10)
                                    .collect(Collectors.toList());
                                newSec.put("tasks", filteredTasks);
                            }
                            return newSec;
                        })
                        .limit(limit != null ? Math.max(1, Math.min(limit, 10)) : 5)
                        .collect(Collectors.toList());
                    backlogMap.put("sections", filteredSections);
                }
            }
            return backlogMap;
        } catch (Exception e) {
            log.warn("[AiTool] Failed to parse backlog for limits, returning raw: {}", e.getMessage());
            return rawBacklog;
        }
    }


    @Tool("Fetch the active sprint board for a project (tasks in the active sprint organized by column).")
    public Object getSprintBoard(
            @P("The ID of the project") Long projectId,
            @P("Optional. Maximum number of active tasks to return. Default 15, max 30.") Integer limit) {
        log.info("[AiTool] getSprintBoard called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        Object rawBoard = sprintQueryPort.getSprintBoard(projectId, userId);
        try {
            Map<String, Object> boardMap = PATCH_OBJECT_MAPPER.convertValue(rawBoard, new TypeReference<Map<String, Object>>() {});
            if (boardMap.containsKey("tasks")) {
                List<Map<String, Object>> tasks = (List<Map<String, Object>>) boardMap.get("tasks");
                if (tasks != null) {
                    List<Map<String, Object>> filteredTasks = tasks.stream()
                        .limit(limit != null ? Math.max(1, Math.min(limit, 30)) : 15)
                        .collect(Collectors.toList());
                    boardMap.put("tasks", filteredTasks);
                }
            }
            return boardMap;
        } catch (Exception e) {
            log.warn("[AiTool] Failed to parse board for limits, returning raw: {}", e.getMessage());
            return rawBoard;
        }
    }


    @Tool("Plan and create a new sprint in a project. Requires confirmation.")
    public Object createSprint(
            @P("The project ID. If missing or not specified, you MUST still call this tool with a null/empty projectId; it will automatically return the form.") Long projectId,
            @P("Name of the sprint, e.g. 'Sprint 3'. If missing or not specified, you MUST still call this tool with a null/empty name; it will automatically return the form.") String name,
            @P("Optional start date in YYYY-MM-DD format") String startDate,
            @P("Optional end date in YYYY-MM-DD format") String endDate,
            @P("Optional goal or objective of the sprint") String goal) {
        log.info("[AiTool] createSprint called for project {}", projectId);
        if (projectId == null || name == null || name.isBlank()) {
            java.util.List<java.util.Map<String, Object>> fields = new java.util.ArrayList<>();
            if (projectId == null) {
                fields.add(java.util.Map.of("name", "projectId", "label", "Dự án", "type", "number", "required", true));
            }
            fields.addAll(java.util.List.of(
                java.util.Map.of("name", "name", "label", "Tên sprint", "type", "text", "required", true),
                java.util.Map.of("name", "startDate", "label", "Ngày bắt đầu", "type", "date"),
                java.util.Map.of("name", "endDate", "label", "Hạn chót", "type", "date"),
                java.util.Map.of("name", "goal", "label", "Mục tiêu", "type", "textarea")
            ));
            return java.util.Map.of(
                "status", "FORM_REQUIRED",
                "form", java.util.Map.of(
                    "title", "Tạo sprint mới",
                    "intent", "createSprint",
                    "fields", fields
                )
            );
        }
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "createSprint",
                "Create planned sprint \"" + name + "\" in project " + projectId,
                args("projectId", projectId, "name", name, "startDate", startDate, "endDate", endDate, "goal", goal),
                null,
                () -> sprintQueryPort.createSprint(projectId, name, startDate, endDate, goal, userId));
    }


    @Tool("Update multiple fields of a planning or active sprint. CRITICAL: Use patchSprint instead if you are partially updating a sprint (like renaming it or changing dates/goal). Requires confirmation.")
    public Object updateSprint(
            @P("The project ID") Long projectId,
            @P("The ID of the sprint") Long sprintId,
            @P("Optional sprint name") String name,
            @P("Optional start date in YYYY-MM-DD format") String startDate,
            @P("Optional end date in YYYY-MM-DD format") String endDate,
            @P("Optional sprint goal") String goal) {
        log.info("[AiTool] updateSprint called for sprint {}", sprintId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateSprint",
                "Update sprint " + sprintId + " in project " + projectId,
                args("projectId", projectId, "sprintId", sprintId, "name", name, "startDate", startDate,
                        "endDate", endDate, "goal", goal),
                null,
                () -> sprintQueryPort.updateSprint(projectId, sprintId, name, startDate, endDate, goal, userId));
    }


    @Tool("Partially update a sprint (e.g. rename it, change goal, or change dates). Send patchData map containing changed fields (name, goal, dates). CRITICAL: If you are changing the name, goal, or dates of a sprint, you MUST use this tool (patchSprint) instead of updateSprint! Requires confirmation.")
    public Object patchSprint(
            @P("The project ID") Long projectId,
            @P("The ID of the sprint") Long sprintId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        Map<String, Object> patch = normalizePatch(patchData);
        log.info("[AiTool] patchSprint called for sprint {} with patch {}", sprintId, patch);
        patch.keySet().forEach(fieldName -> validatePatchField(fieldName, Set.of("name", "startDate", "endDate", "goal")));
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        String name = stringPatchValue(patch, "name");
        String startDate = stringPatchValue(patch, "startDate");
        String endDate = stringPatchValue(patch, "endDate");
        String goal = stringPatchValue(patch, "goal");

        return pendingAiActionService.create(
                userId,
                sessionId,
                "patchSprint",
                "Patch sprint " + sprintId + " in project " + projectId,
                args("projectId", projectId, "sprintId", sprintId, "patch", patch, "reason", reason),
                args("projectId", projectId, "sprintId", sprintId, "patch", patch, "reason", reason),
                () -> sprintQueryPort.updateSprint(projectId, sprintId, name, startDate, endDate, goal, userId));
    }


    @Tool("Delete a planned sprint by sprint ID and project ID. Requires confirmation.")
    public Object deleteSprint(
            @P("The project ID") Long projectId,
            @P("The ID of the sprint") Long sprintId) {
        log.info("[AiTool] deleteSprint called for sprint {}", sprintId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteSprint",
                "Delete sprint " + sprintId + " in project " + projectId,
                args("projectId", projectId, "sprintId", sprintId),
                null,
                () -> {
                    sprintQueryPort.deleteSprint(projectId, sprintId, userId);
                    return "Sprint deleted successfully";
                });
    }


    @Tool("Start a planned sprint in a project. Requires confirmation.")
    public Object startSprint(
            @P("The project ID") Long projectId,
            @P("The ID of the sprint to start") Long sprintId) {
        log.info("[AiTool] startSprint called for sprint {}", sprintId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "startSprint",
                "Start planned sprint " + sprintId + " in project " + projectId,
                args("projectId", projectId, "sprintId", sprintId),
                null,
                () -> sprintQueryPort.startSprint(projectId, sprintId, userId));
    }


    @Tool("Mark an active sprint as completed in a project. Requires confirmation.")
    public Object completeSprint(
            @P("The project ID") Long projectId,
            @P("The ID of the sprint to complete") Long sprintId) {
        log.info("[AiTool] completeSprint called for sprint {}", sprintId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "completeSprint",
                "Complete active sprint " + sprintId + " in project " + projectId,
                args("projectId", projectId, "sprintId", sprintId),
                null,
                () -> sprintQueryPort.completeSprint(projectId, sprintId, userId));
    }


    @Tool("Move or assign a task to a sprint (or set sprintId=null to move to backlog). Requires confirmation.")
    public Object assignTaskToSprint(
            @P("The ID of the task") Long taskId,
            @P("The ID of the target sprint, or null to move it to the backlog") Long sprintId) {
        log.info("[AiTool] assignTaskToSprint called for task {} -> sprint {}", taskId, sprintId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        String desc = sprintId == null ? "Move task " + taskId + " to the backlog" : "Move task " + taskId + " to sprint " + sprintId;
        return pendingAiActionService.create(
                userId,
                sessionId,
                "assignTaskToSprint",
                desc,
                args("taskId", taskId, "sprintId", sprintId),
                null,
                () -> sprintQueryPort.assignTaskToSprint(taskId, sprintId, userId));
    }


}
