package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.aiquery.dto.*;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
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
public class TaskAiTools {

    private final TaskCommandPort taskCommandPort;
    private final ProjectInsightsPort projectInsightsPort;
    private final PendingAiActionService pendingAiActionService;

    @Tool("Get task details (title, description, status, priority, difficulty, required skills, due date) by task ID.")
    public TaskDetailDto getTaskDetails(@P("The ID of the task") String taskId) {
        log.info("[AiTool] getTaskDetails called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getTaskDetails(toLong(taskId), userId);
    }


    @Tool("Update required skills for a task (comma-separated skill names). Requires confirmation.")
    public Object updateTaskRequiredSkills(
            @P("The ID of the task") Long taskId,
            @P("Comma-separated active skill names from the system skill directory") String skills) {
        log.info("[AiTool] updateTaskRequiredSkills called for task {} -> {}", taskId, skills);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateTaskRequiredSkills",
                "Update required skills for task " + taskId + " to " + skills,
                args("taskId", taskId, "skills", skills),
                null,
                () -> taskCommandPort.updateTaskRequiredSkills(taskId, skills, userId));
    }


    public record AiQueryTaskDto(
            Long id,
            Long projectId,
            String title,
            String status,
            String priority,
            Integer difficultyLevel,
            Long assigneeId,
            String assigneeName,
            String dueDate,
            String description) {
    }


    @Tool("Search, query, and filter tasks in a project. All filters except projectId are optional (can be null/empty). Supports keyword search and sorting.")
    public Object queryTasks(
            @P("The ID of the project") String projectId,
            @P("Optional. Filter by assignee ID. Can be null/empty.") String assigneeId,
            @P("Optional. Filter by status (TODO, IN_PROGRESS, REVIEW, DONE). Can be null/empty.") String status,
            @P("Optional. Set to true to find overdue tasks (dueDate < today). Can be null/empty.") Boolean isOverdue,
            @P("Optional. Set to true to find tasks with dueDate = today. Can be null/empty.") Boolean dueToday,
            @P("Optional. Set to true to find tasks that have NO assignee. Can be null/empty.") Boolean unassignedOnly,
            @P("Optional. Search keyword for task title or description. Can be null/empty.") String searchTerm,
            @P("Optional. Field to sort by: 'title', 'dueDate', 'priority', 'difficultyLevel' (default 'title')") String sortBy,
            @P("Optional. Sort direction: 'ASC' or 'DESC' (default 'ASC')") String sortDirection,
            @P("Optional. Maximum number of results to return (default 10, max 50).") Integer limit) {
        log.info("[AiTool] queryTasks called for project {} status={} assignee={} search={} sortBy={} sortDir={}", projectId, status, assigneeId, searchTerm, sortBy, sortDirection);
        Long userId = ToolExecutionContext.requireUserId();

        java.util.List<?> rawTasks;
        if (Boolean.TRUE.equals(unassignedOnly)) {
            rawTasks = taskCommandPort.getUnassignedTasksByProject(toLong(projectId), userId);
        } else {
            rawTasks = taskCommandPort.getTasksByProject(toLong(projectId), userId);
        }

        java.time.LocalDate today = java.time.LocalDate.now();
        String sortField = sortBy != null ? sortBy.trim().toLowerCase() : "title";
        String direction = sortDirection != null ? sortDirection.trim().toUpperCase() : "ASC";
        boolean isAsc = !"DESC".equals(direction);

        java.util.Comparator<AiQueryTaskDto> comparator = (t1, t2) -> {
            int comp = 0;
            switch (sortField) {
                case "duedate":
                    if (t1.dueDate() == null && t2.dueDate() == null) comp = 0;
                    else if (t1.dueDate() == null) comp = 1; // null due dates go last
                    else if (t2.dueDate() == null) comp = -1;
                    else comp = t1.dueDate().compareTo(t2.dueDate());
                    break;
                case "priority":
                    comp = String.valueOf(t1.priority()).compareToIgnoreCase(String.valueOf(t2.priority()));
                    break;
                case "difficultylevel":
                    int d1 = t1.difficultyLevel() != null ? t1.difficultyLevel() : 0;
                    int d2 = t2.difficultyLevel() != null ? t2.difficultyLevel() : 0;
                    comp = Integer.compare(d1, d2);
                    break;
                case "title":
                default:
                    comp = String.valueOf(t1.title()).compareToIgnoreCase(String.valueOf(t2.title()));
                    break;
            }
            return isAsc ? comp : -comp;
        };

        return rawTasks.stream().map(task -> {
            if (task instanceof com.taskpilot.contracts.aiquery.dto.TaskDetailDto d) {
                return new AiQueryTaskDto(d.id(), d.projectId(), d.title(), d.status(), d.priority(), d.difficultyLevel(), d.assigneeId(), d.assigneeName(), d.dueDate(), d.description());
            } else if (task instanceof com.taskpilot.contracts.aiquery.dto.TaskSummaryDto s) {
                return new AiQueryTaskDto(s.id(), s.projectId(), s.title(), s.status(), s.priority(), s.difficultyLevel(), s.assigneeId(), s.assigneeName(), s.dueDate(), "");
            }
            return null;
        })
        .filter(dto -> dto != null)
        .filter(dto -> !Boolean.TRUE.equals(unassignedOnly) || dto.assigneeId() == null)
        .filter(dto -> assigneeId == null || assigneeId.isBlank() || assigneeId.equals(String.valueOf(dto.assigneeId())))
        .filter(dto -> status == null || status.isBlank() || status.equalsIgnoreCase(dto.status()))
        .filter(dto -> searchTerm == null || searchTerm.isBlank() ||
                (dto.title() != null && dto.title().toLowerCase().contains(searchTerm.toLowerCase())) ||
                (dto.description() != null && dto.description().toLowerCase().contains(searchTerm.toLowerCase())))
        .filter(dto -> {
            if (dto.dueDate() == null || dto.dueDate().isBlank()) {
                return !Boolean.TRUE.equals(isOverdue) && !Boolean.TRUE.equals(dueToday);
            }
            try {
                String dateStr = dto.dueDate().length() > 10 ? dto.dueDate().substring(0, 10) : dto.dueDate();
                java.time.LocalDate due = java.time.LocalDate.parse(dateStr);
                if (Boolean.TRUE.equals(dueToday)) return due.equals(today);
                if (Boolean.TRUE.equals(isOverdue)) return due.isBefore(today);
            } catch (Exception e) { return false; }
            return true;
        })
        .sorted(comparator)
        .limit(limit != null ? Math.max(1, Math.min(limit, 50)) : 10)
        .toList();
    }


    @Tool("Fetch subtasks belonging to a specific parent task ID.")
    public Object getSubtasks(@P("The ID of the parent task") Long parentTaskId) {
        log.info("[AiTool] getSubtasks called for parent task {}", parentTaskId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getSubtasks(parentTaskId, userId);
    }


    @Tool("Update the status of a task (TODO, IN_PROGRESS, REVIEW, DONE). Requires confirmation.")
    public Object updateTaskStatus(
            @P("The ID of the task") Long taskId,
            @P("The new status (TODO, IN_PROGRESS, REVIEW, DONE)") String status) {
        log.info("[AiTool] updateTaskStatus called for task {} -> {}", taskId, status);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateTaskStatus",
                "Update task " + taskId + " status to " + status,
                args("taskId", taskId, "status", status),
                null,
                () -> taskCommandPort.updateTaskStatus(taskId, status, userId));
    }


    @Tool("Update multiple fields of a task. Omit unchanged parameters. Requires confirmation.")
    public Object updateTask(
            @P("The ID of the task") Long taskId,
            @P("Optional title") String title,
            @P("Optional description") String description,
            @P("Optional status (TODO, IN_PROGRESS, REVIEW, DONE)") String status,
            @P("Optional priority (LOW, MEDIUM, HIGH, URGENT)") String priority,
            @P("Optional kanban position") Float position,
            @P("Optional full replacement label ID list") List<Long> labelIds,
            @P("Optional difficulty 1-10. Note: send as string like '5'") String difficultyLevel,
            @P("Optional full replacement required skill ID list") List<Long> requiredSkillIds,
            @P("Optional assignee user ID") Long assigneeId,
            @P("Optional start date as ISO-8601 instant or YYYY-MM-DD") String startDate,
            @P("Optional due date as ISO-8601 instant or YYYY-MM-DD") String dueDate) {
        log.info("[AiTool] updateTask called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        Integer parsedDifficultyLevel = null;
        if (difficultyLevel != null && !difficultyLevel.isBlank()) {
            try { parsedDifficultyLevel = Integer.valueOf(difficultyLevel.trim()); } catch (Exception ignored) {}
        }
        final Integer finalDifficultyLevel = parsedDifficultyLevel;

        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateTask",
                "Update task " + taskId,
                args("taskId", taskId, "title", title, "description", description, "status", status,
                        "priority", priority, "position", position, "labelIds", labelIds,
                        "difficultyLevel", finalDifficultyLevel, "requiredSkillIds", requiredSkillIds,
                        "assigneeId", assigneeId, "startDate", startDate, "dueDate", dueDate),
                null,
                () -> taskCommandPort.updateTask(taskId, title, description, status, priority, position, labelIds,
                        finalDifficultyLevel, requiredSkillIds, assigneeId, startDate, dueDate, userId));
    }


    @Tool("Partially update a task. Send patchData map containing changed fields (title, status, assigneeId, dueDate, etc). Requires confirmation.")
    public Object patchTask(
            @P("The ID of the task") Long taskId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        Map<String, Object> patch = normalizePatch(patchData);
        log.info("[AiTool] patchTask called for task {} with patch {}", taskId, patch);
        if (patch.isEmpty()) {
            return "patchTask requires at least one changed field. Use argument patchData with fields such as "
                    + "{\"dueDate\":\"2026-07-31\",\"assigneeId\":9}; do not create a confirmation for an empty patch.";
        }
        
        Set<String> allowedFields = Set.of("title", "description", "status", "priority", "position", "labelIds",
                "difficultyLevel", "requiredSkillIds", "assigneeId", "startDate", "dueDate");
        patch.keySet().forEach(fieldName -> validatePatchField(fieldName, allowedFields));
        
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        String title = stringPatchValue(patch, "title");
        String description = stringPatchValue(patch, "description");
        String status = stringPatchValue(patch, "status");
        String priority = stringPatchValue(patch, "priority");
        Float position = floatPatchValue(patch, "position");
        List<Long> labelIds = longListPatchValue(patch, "labelIds");
        Integer difficultyLevel = integerPatchValue(patch, "difficultyLevel");
        List<Long> requiredSkillIds = longListPatchValue(patch, "requiredSkillIds");
        Long assigneeId = longPatchValue(patch, "assigneeId");
        String startDate = stringPatchValue(patch, "startDate");
        String dueDate = stringPatchValue(patch, "dueDate");

        return pendingAiActionService.create(
                userId,
                sessionId,
                "patchTask",
                "Patch task " + taskId,
                args("taskId", taskId, "patch", patch, "reason", reason),
                args("taskId", taskId, "patch", patch, "reason", reason),
                () -> taskCommandPort.updateTask(taskId, title, description, status, priority, position, labelIds,
                        difficultyLevel, requiredSkillIds, assigneeId, startDate, dueDate, userId));
    }


    @Tool("Delete a task by task ID. Requires confirmation.")
    public Object deleteTask(@P("The ID of the task to delete") Long taskId) {
        log.info("[AiTool] deleteTask called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteTask",
                "Delete task " + taskId,
                args("taskId", taskId),
                null,
                () -> {
                    taskCommandPort.deleteTask(taskId, userId);
                    return "Task deleted successfully";
                });
    }


    @Tool("Move a task on the kanban board by updating status and position. Requires confirmation.")
    public Object moveTaskKanban(
            @P("The ID of the task") String taskId,
            @P("Target status (TODO, IN_PROGRESS, REVIEW, DONE)") String status,
            @P("Target kanban position") Float position) {
        log.info("[AiTool] moveTaskKanban called for task {} -> {} @ {}", taskId, status, position);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        Long resolvedTaskId = toLong(taskId);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "moveTaskKanban",
                "Move task " + taskId + " to " + status,
                args("taskId", resolvedTaskId, "status", status, "position", position),
                null,
                () -> taskCommandPort.moveTaskKanban(resolvedTaskId, status, position, userId));
    }


    @Tool("""
            Create a new task in a project. Required: projectId, title. Optional: description, priority, sprintId, difficultyLevel (1-10 as string), labelIds, requiredSkillIds, assigneeId, startDate, dueDate, parentId.
            CRITICAL INSTRUCTION: If you do not know the user's projectId, DO NOT output a form! You MUST call queryProjects tool right now to get the project list!
            Requires confirmation.
            """)
    public Object createTask(
            @P("Optional: The project ID. If creating a subtask and project ID is not explicitly given, you should resolve it or default it based on the parent task.") Object projectId,
            @P("Title of the task. If missing or not specified, you MUST still call this tool with a null/empty title; it will automatically return the form.") String title,
            @P("Priority: LOW, MEDIUM, HIGH, or URGENT. Default to MEDIUM if not specified.") String priority,
            @P("Optional description") String description,
            @P("Optional sprint ID to place the task in") Object sprintId,
            @P("Optional task difficulty 1-10. Send as string e.g. '5'") String difficultyLevel,
            @P("Optional label ID list") Object labelIds,
            @P("Optional required skill ID list") Object requiredSkillIds,
            @P("Optional assignee user ID. IMPORTANT: Leave NULL unless explicitly requested by user. Do NOT guess or invent IDs.") Object assigneeId,
            @P("Optional start date as ISO-8601 or YYYY-MM-DD") String startDate,
            @P("Optional due date as ISO-8601 or YYYY-MM-DD") String dueDate,
            @P("Optional parent task ID if this is a subtask") Object parentId) {
        Long resolvedProjectId = toLong(projectId);
        Long resolvedParentId = toLong(parentId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        if (title == null || title.isBlank()) {
            java.util.List<java.util.Map<String, Object>> fields = new java.util.ArrayList<>();
            if (resolvedProjectId == null && resolvedParentId == null) {
                fields.add(java.util.Map.of("name", "projectId", "label", "Dự án", "type", "number", "required", true));
            }
            fields.addAll(java.util.List.of(
                java.util.Map.of("name", "title", "label", "Tiêu đề task", "type", "text", "required", true),
                java.util.Map.of("name", "description", "label", "Mô tả", "type", "textarea"),
                java.util.Map.of("name", "priority", "label", "Độ ưu tiên", "type", "select", "options", java.util.List.of("LOW", "MEDIUM", "HIGH", "URGENT"), "required", true),
                java.util.Map.of("name", "assigneeId", "label", "Người thực hiện", "type", "number"),
                java.util.Map.of("name", "startDate", "label", "Ngày bắt đầu", "type", "date"),
                java.util.Map.of("name", "dueDate", "label", "Hạn chót", "type", "date"),
                java.util.Map.of("name", "sprintId", "label", "Sprint", "type", "number"),
                java.util.Map.of("name", "difficultyLevel", "label", "Độ khó (1-10)", "type", "number", "min", 1, "max", 10),
                java.util.Map.of("name", "labelIds", "label", "Nhãn", "type", "multiselect"),
                java.util.Map.of("name", "requiredSkillIds", "label", "Kỹ năng yêu cầu", "type", "multiselect")
            ));
            return java.util.Map.of(
                "status", "FORM_REQUIRED",
                "form", java.util.Map.of(
                    "title", "Tạo task mới",
                    "intent", "createTask",
                    "fields", fields
                )
            );
        }

        if (resolvedProjectId == null && resolvedParentId != null) {
            try {
                TaskDetailDto parentTask = taskCommandPort.getTaskDetails(resolvedParentId, userId);
                if (parentTask != null) {
                    resolvedProjectId = parentTask.projectId();
                    log.info("[AiTool] Resolved project ID {} from parent task {}", resolvedProjectId, resolvedParentId);
                }
            } catch (Exception e) {
                log.warn("[AiTool] Failed to resolve project ID from parent task {}: {}", resolvedParentId, e.getMessage());
            }
        }

        log.info("[AiTool] createTask called for project {} (parent={})", resolvedProjectId, resolvedParentId);

        Integer parsedDifficultyLevel = null;
        if (difficultyLevel != null && !difficultyLevel.isBlank()) {
            try { parsedDifficultyLevel = Integer.valueOf(difficultyLevel.trim()); } catch (Exception ignored) {}
        }
        final Integer finalDifficultyLevel = parsedDifficultyLevel;

        Long resolvedSprintId = toLong(sprintId);
        Long resolvedAssigneeId = toLong(assigneeId);
        List<Long> resolvedLabelIds = toLongList(labelIds);
        List<Long> resolvedRequiredSkillIds = toLongList(requiredSkillIds);
        String resolvedDescription = cleanString(description);
        String resolvedStartDate = cleanString(startDate);
        String resolvedDueDate = cleanString(dueDate);

        final Long finalProjectId = resolvedProjectId;
        return pendingAiActionService.create(
                userId,
                sessionId,
                "createTask",
                "Create task \"" + title + "\" in project " + finalProjectId,
                args("projectId", finalProjectId, "title", title, "priority", priority, "description", resolvedDescription,
                        "sprintId", resolvedSprintId, "difficultyLevel", finalDifficultyLevel, "labelIds", resolvedLabelIds,
                        "requiredSkillIds", resolvedRequiredSkillIds, "assigneeId", resolvedAssigneeId,
                        "startDate", resolvedStartDate, "dueDate", resolvedDueDate, "parentId", resolvedParentId),
                null,
                () -> taskCommandPort.createTask(finalProjectId, title, resolvedDescription, priority, null,
                        resolvedParentId, resolvedSprintId, finalDifficultyLevel, resolvedLabelIds, resolvedRequiredSkillIds,
                        resolvedAssigneeId, resolvedStartDate, resolvedDueDate, userId));
    }



}
