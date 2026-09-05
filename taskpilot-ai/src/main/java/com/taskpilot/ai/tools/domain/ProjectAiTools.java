package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.assignment.dto.ProjectDueDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.aiquery.dto.*;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

import static com.taskpilot.ai.tools.support.AiToolSupport.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectAiTools {

    private final ProjectInsightsPort projectInsightsPort;
    private final ProjectMemberPort projectMemberPort;
    private final PendingAiActionService pendingAiActionService;
    @Tool("Search for projects the current user participates in. All filters are optional (can be null/empty). Supports keyword search and sorting.")
    public Object queryProjects(
            @P("Optional. Status to filter by (e.g. PLANNING, ACTIVE, COMPLETED, ARCHIVED). Use null/empty to get all statuses.") String status,
            @P("Optional. Role of the user in the project (e.g. MANAGER, MEMBER). Use null/empty to get all roles.") String role,
            @P("Optional. Search keyword for project name or description") String searchTerm,
            @P("Optional. Field to sort by: 'name', 'startDate', 'endDate', 'status' (default 'name')") String sortBy,
            @P("Optional. Sort direction: 'ASC' or 'DESC' (default 'ASC')") String sortDirection,
            @P("Optional. Maximum number of projects to return (default 10, max 20)") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] queryProjects called for user {} status={} role={} search={} sortBy={} sortDir={}", userId, status, role, searchTerm, sortBy, sortDirection);
        List<ProjectOverviewDto> allProjects = projectInsightsPort.getMyProjects(userId);

        String sortField = sortBy != null ? sortBy.trim().toLowerCase() : "name";
        String direction = sortDirection != null ? sortDirection.trim().toUpperCase() : "ASC";
        boolean isAsc = !"DESC".equals(direction);

        java.util.Comparator<ProjectOverviewDto> comparator = (p1, p2) -> {
            int comp = 0;
            switch (sortField) {
                case "status":
                    comp = String.valueOf(p1.status()).compareToIgnoreCase(String.valueOf(p2.status()));
                    break;
                case "startdate":
                    if (p1.startDate() == null && p2.startDate() == null) comp = 0;
                    else if (p1.startDate() == null) comp = -1;
                    else if (p2.startDate() == null) comp = 1;
                    else comp = p1.startDate().compareTo(p2.startDate());
                    break;
                case "enddate":
                    if (p1.endDate() == null && p2.endDate() == null) comp = 0;
                    else if (p1.endDate() == null) comp = -1;
                    else if (p2.endDate() == null) comp = 1;
                    else comp = p1.endDate().compareTo(p2.endDate());
                    break;
                case "name":
                default:
                    comp = String.valueOf(p1.name()).compareToIgnoreCase(String.valueOf(p2.name()));
                    break;
            }
            return isAsc ? comp : -comp;
        };

        // Apply filters in-memory for Stage 1
        List<Map<String, Object>> filtered = allProjects.stream()
                .filter(p -> status == null || status.isBlank() || status.equalsIgnoreCase(p.status()))
                .filter(p -> role == null || role.isBlank() || role.equalsIgnoreCase(p.role()))
                .filter(p -> searchTerm == null || searchTerm.isBlank() || 
                        (p.name() != null && p.name().toLowerCase().contains(searchTerm.toLowerCase())) ||
                        (p.description() != null && p.description().toLowerCase().contains(searchTerm.toLowerCase())))
                .sorted(comparator)
                .map(p -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("projectId", p.projectId());
                    map.put("name", p.name());
                    map.put("role", p.role());
                    map.put("status", p.status());
                    map.put("startDate", p.startDate() != null ? p.startDate().toString() : "");
                    map.put("endDate", p.endDate() != null ? p.endDate().toString() : "");
                    return map;
                })
                .limit(limit != null ? Math.max(1, Math.min(limit, 20)) : 10)
                .collect(Collectors.toList());

        return Map.of("results", filtered, "totalMatched", filtered.size());
    }


    @Tool("Get the status, progress, and health summary of a project by its project ID.")
    public ProjectStatusDto getProjectStatus(@P("The ID of the project to query") String projectId) {
        log.info("[AiTool] getProjectStatus called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return projectInsightsPort.getProjectStatus(toLong(projectId), userId);
    }


    @Tool("Fetch all labels configured for a project by project ID.")
    public List<LabelSummaryDto> getProjectLabels(@P("The ID of the project") String projectId) {
        log.info("[AiTool] getProjectLabels called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return projectInsightsPort.getProjectLabels(toLong(projectId), userId);
    }


    @Tool("Fetch projects due soon within a number-of-days window (daysAhead, default is 7).")
    public String getUpcomingProjects(
            @P("Number of days ahead to check (default 7). Note: send as string like '7'") String daysAhead) {
        int parsedDays = 7;
        if (daysAhead != null && !daysAhead.isBlank()) {
            try { parsedDays = Integer.parseInt(daysAhead.trim()); } catch (Exception ignored) {}
        }
        int safeDays = Math.max(1, Math.min(90, parsedDays));
        Long userId = ToolExecutionContext.requireUserId();

        LocalDate fromDate = LocalDate.now();
        LocalDate toDate = fromDate.plusDays(safeDays);

        try {
            return PATCH_OBJECT_MAPPER.writeValueAsString(
                projectMemberPort.findUpcomingProjects(userId, fromDate, toDate, 20)
            );
        } catch (Exception e) {
            log.error("[AiTool] Failed to serialize upcoming projects", e);
            return "[]";
        }
    }


    @Tool("Find projects due within a concrete date range (fromDate and toDate in YYYY-MM-DD format).")
    public String findProjectsDue(
            @P("Start date in YYYY-MM-DD format") String fromDate,
            @P("End date in YYYY-MM-DD format") String toDate) {
        
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(fromDate);
            to = LocalDate.parse(toDate);
        } catch (DateTimeParseException | NullPointerException ex) {
            return "[]";
        }

        if (to.isBefore(from)) {
            return "[]";
        }

        Long userId = ToolExecutionContext.requireUserId();
        try {
            return PATCH_OBJECT_MAPPER.writeValueAsString(
                projectMemberPort.findUpcomingProjects(userId, from, to, 20)
            );
        } catch (Exception e) {
            log.error("[AiTool] Failed to serialize projects due", e);
            return "[]";
        }
    }


    @Tool("Create a new label in a project with optional name and hex color. Requires confirmation.")
    public Object createProjectLabel(
            @P("The ID of the project") Long projectId,
            @P("Label name") String name,
            @P("Optional hex color, e.g. #6366F1") String color) {
        log.info("[AiTool] createProjectLabel called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "createProjectLabel",
                "Create label \"" + name + "\" in project " + projectId,
                args("projectId", projectId, "name", name, "color", color),
                args("projectId", projectId, "name", name, "color", color),
                () -> projectInsightsPort.createProjectLabel(projectId, name, color, userId));
    }


    @Tool("Delete a label from a project by project ID and label ID. Requires confirmation.")
    public Object deleteProjectLabel(
            @P("The ID of the project") Long projectId,
            @P("The ID of the label") Long labelId) {
        log.info("[AiTool] deleteProjectLabel called for project {} label {}", projectId, labelId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteProjectLabel",
                "Delete label " + labelId + " from project " + projectId,
                args("projectId", projectId, "labelId", labelId),
                null,
                () -> {
                    projectInsightsPort.deleteProjectLabel(projectId, labelId, userId);
                    return "Label deleted successfully";
                });
    }


    @Tool("Create a new project. Supports optional description, startDate, endDate. Requires confirmation.")
    public Object createProject(
            @P("Name of the project. If missing or not specified, you MUST still call this tool with a null/empty name; it will automatically return the form.") String projectName,
            @P("Optional description of the project") String description,
            @P("Optional start date in YYYY-MM-DD format") String startDate,
            @P("Optional end date in YYYY-MM-DD format") String endDate) {
        log.info("[AiTool] createProject called with name={}", projectName);
        if (projectName == null || projectName.isBlank()) {
            return java.util.Map.of(
                "status", "FORM_REQUIRED",
                "form", java.util.Map.of(
                    "title", "Tạo dự án mới",
                    "intent", "createProject",
                    "fields", java.util.List.of(
                        java.util.Map.of("name", "projectName", "label", "Tên dự án", "type", "text", "required", true),
                        java.util.Map.of("name", "description", "label", "Mô tả", "type", "textarea")
                    )
                )
            );
        }
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "createProject",
                "Create new project \"" + projectName + "\"",
                args("projectName", projectName, "description", description, "startDate", startDate, "endDate", endDate),
                null,
                () -> projectInsightsPort.createProject(projectName, description, startDate, endDate, userId));
    }


    @Tool("Update multiple fields of an existing project. Omit unchanged parameters. Requires confirmation.")
    public Object updateProject(
            @P("The ID of the project to update") Long projectId,
            @P("Optional name of the project") String name,
            @P("Optional description") String description,
            @P("Optional status (ACTIVE, COMPLETED, ARCHIVED)") String status,
            @P("Optional heuristic mode (BALANCED, SKILL_FIT_ONLY, WORKLOAD_ONLY)") String heuristicMode,
            @P("Optional workflow mode (STANDARD, SCRUM, KANBAN)") String workflowMode,
            @P("Optional start date in YYYY-MM-DD format") String startDate,
            @P("Optional end date in YYYY-MM-DD format") String endDate) {
        log.info("[AiTool] updateProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateProject",
                "Update project details for project ID " + projectId,
                args("projectId", projectId, "name", name, "description", description, "status", status,
                        "heuristicMode", heuristicMode, "workflowMode", workflowMode, "startDate", startDate, "endDate", endDate),
                null,
                () -> projectInsightsPort.updateProject(projectId, name, description, status, heuristicMode, workflowMode, startDate, endDate, userId));
    }


    @Tool("Partially update a project. Send patchData map containing changed fields (name, status, endDate, etc). Requires confirmation.")
    public Object patchProject(
            @P("The ID of the project to update") Long projectId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        Map<String, Object> patch = normalizePatch(patchData);
        log.info("[AiTool] patchProject called for project {} with patch {}", projectId, patch);
        
        Set<String> allowedFields = Set.of("name", "description", "status", "heuristicMode", "workflowMode", "startDate", "endDate");
        patch.keySet().forEach(fieldName -> validatePatchField(fieldName, allowedFields));
        
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        String name = stringPatchValue(patch, "name");
        String description = stringPatchValue(patch, "description");
        String status = stringPatchValue(patch, "status");
        String heuristicMode = stringPatchValue(patch, "heuristicMode");
        String workflowMode = stringPatchValue(patch, "workflowMode");
        String startDate = stringPatchValue(patch, "startDate");
        String endDate = stringPatchValue(patch, "endDate");

        return pendingAiActionService.create(
                userId,
                sessionId,
                "patchProject",
                "Patch project " + projectId,
                args("projectId", projectId, "patch", patch, "reason", reason),
                args("projectId", projectId, "patch", patch, "reason", reason),
                () -> projectInsightsPort.updateProject(projectId, name, description, status, heuristicMode,
                        workflowMode, startDate, endDate, userId));
    }


    @Tool("Join an existing project using an invitation code. Requires confirmation.")
    public Object joinProject(@P("The invitation project code") String projectCode) {
        log.info("[AiTool] joinProject called with code={}", projectCode);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "joinProject",
                "Join project with code \"" + projectCode + "\"",
                args("projectCode", projectCode),
                null,
                () -> projectInsightsPort.joinProject(projectCode, userId));
    }


    @Tool("Leave a project by project ID. Requires confirmation.")
    public Object leaveProject(@P("The ID of the project to leave") Long projectId) {
        log.info("[AiTool] leaveProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "leaveProject",
                "Leave project ID " + projectId,
                args("projectId", projectId),
                null,
                () -> {
                    projectInsightsPort.leaveProject(projectId, userId);
                    return "Left project successfully";
                });
    }


    @Tool("Archive a project to make it read-only. Requires confirmation.")
    public Object archiveProject(@P("The ID of the project to archive") Long projectId) {
        log.info("[AiTool] archiveProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "archiveProject",
                "Archive project ID " + projectId,
                args("projectId", projectId),
                null,
                () -> {
                    projectInsightsPort.archiveProject(projectId, userId);
                    return "Project archived successfully";
                });
    }


    @Tool("Restore an archived project to active status. Requires confirmation.")
    public Object restoreProject(@P("The ID of the project to restore") Long projectId) {
        log.info("[AiTool] restoreProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "restoreProject",
                "Restore project ID " + projectId,
                args("projectId", projectId),
                null,
                () -> {
                    projectInsightsPort.restoreProject(projectId, userId);
                    return "Project restored successfully";
                });
    }


    @Tool("Permanently delete a project and all its data. Requires confirmation.")
    public Object deleteProject(@P("The ID of the project to delete") Long projectId) {
        log.info("[AiTool] deleteProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteProject",
                "Permanently delete project ID " + projectId,
                args("projectId", projectId),
                null,
                () -> {
                    projectInsightsPort.deleteProject(projectId, userId);
                    return "Project deleted successfully";
                });
    }


}
