package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.task.PatchTaskCommand;
import com.taskpilot.contracts.task.ReopenTaskCommand;
import com.taskpilot.contracts.task.ReviewDecision;
import com.taskpilot.contracts.task.ReviewTaskCommand;
import com.taskpilot.contracts.task.SubmitTaskCommand;
import com.taskpilot.contracts.task.SupersedeEvaluationCommand;
import com.taskpilot.contracts.task.TaskDateField;
import com.taskpilot.contracts.task.TaskDatePolicy;
import com.taskpilot.contracts.task.TaskWorkflowCommandPort;
import com.taskpilot.contracts.task.WithdrawTaskCommand;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.exception.BusinessException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.taskpilot.ai.tools.support.AiToolSupport.*;

@Slf4j
@Component
public class TaskAiTools {

    private final TaskCommandPort taskCommandPort;
    private final ProjectInsightsPort projectInsightsPort;
    private final PendingAiActionService pendingAiActionService;
    private final Clock clock;
    private TaskWorkflowCommandPort taskWorkflowCommandPort;

    @Autowired(required = false)
    public void setTaskWorkflowCommandPort(TaskWorkflowCommandPort taskWorkflowCommandPort) {
        this.taskWorkflowCommandPort = taskWorkflowCommandPort;
    }

    @Autowired
    public TaskAiTools(
            TaskCommandPort taskCommandPort,
            ProjectInsightsPort projectInsightsPort,
            PendingAiActionService pendingAiActionService) {
        this(taskCommandPort, projectInsightsPort, pendingAiActionService, Clock.systemUTC());
    }

    public TaskAiTools(
            TaskCommandPort taskCommandPort,
            ProjectInsightsPort projectInsightsPort,
            PendingAiActionService pendingAiActionService,
            Clock clock) {
        this.taskCommandPort = taskCommandPort;
        this.projectInsightsPort = projectInsightsPort;
        this.pendingAiActionService = pendingAiActionService;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Tool("Get task details (title, description, status, priority, difficulty, required skills, due date, version) by task ID.")
    public TaskDetailDto getTaskDetails(@P("The ID of the task") String taskId) {
        log.info("[AiTool] getTaskDetails called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getTaskDetails(toLong(taskId), userId);
    }

    @Tool("Update required skills for a task (comma-separated skill names). Requires expectedTaskVersion and confirmation.")
    public Object updateTaskRequiredSkills(
            @P("The ID of the task") Long taskId,
            @P("Comma-separated active skill names from the system skill directory") String skills,
            @P("The task version observed by caller (required)") Long expectedTaskVersion) {
        log.info("[AiTool] updateTaskRequiredSkills called for task {} (v={}) -> {}", taskId, expectedTaskVersion,
                skills);
        requireExpectedTaskVersion(expectedTaskVersion);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        UUID requestId = UUID.randomUUID();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateTaskRequiredSkills",
                "Update required skills for task " + taskId + " to " + skills,
                args("taskId", taskId, "skills", skills, "expectedTaskVersion", expectedTaskVersion,
                        "requestId", requestId.toString()),
                null,
                () -> taskCommandPort.updateTaskRequiredSkills(taskId, skills, expectedTaskVersion, requestId, userId));
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
            String description,
            Long version) {
    }

    @Tool("Search, query, and filter tasks in a project. All filters except projectId are optional (can be null/empty). Supports keyword search and sorting.")
    public Object queryTasks(
            @P("The ID of the project") String projectId,
            @P("Optional. Filter by assignee ID. Can be null/empty.") String assigneeId,
            @P("Optional. Filter by status (TODO, IN_PROGRESS, REVIEW, DONE). Can be null/empty.") String status,
            @P("Optional. Set to true to find overdue tasks (dueDate < now). Can be null/empty.") Boolean isOverdue,
            @P("Optional. Set to true to find tasks due today in the project timezone. Can be null/empty.") Boolean dueToday,
            @P("Optional. Set to true to find tasks that have NO assignee. Can be null/empty.") Boolean unassignedOnly,
            @P("Optional. Search keyword for task title or description. Can be null/empty.") String searchTerm,
            @P("Optional. Field to sort by: 'title', 'dueDate', 'priority', 'difficultyLevel' (default 'title')") String sortBy,
            @P("Optional. Sort direction: 'ASC' or 'DESC' (default 'ASC')") String sortDirection,
            @P("Optional. Maximum number of results to return (default 10, max 50).") Integer limit) {
        log.info("[AiTool] queryTasks called for project {} status={} assignee={} search={} sortBy={} sortDir={}",
                projectId, status, assigneeId, searchTerm, sortBy, sortDirection);
        Long userId = ToolExecutionContext.requireUserId();
        Long resolvedProjectId = toLong(projectId);

        List<?> rawTasks;
        if (Boolean.TRUE.equals(unassignedOnly)) {
            rawTasks = taskCommandPort.getUnassignedTasksByProject(resolvedProjectId, userId);
        } else {
            rawTasks = taskCommandPort.getTasksByProject(resolvedProjectId, userId);
        }

        String fallbackZone = resolveProjectTimezone(resolvedProjectId, userId);
        String sortField = sortBy != null ? sortBy.trim().toLowerCase() : "title";
        String direction = sortDirection != null ? sortDirection.trim().toUpperCase() : "ASC";
        boolean isAsc = !"DESC".equals(direction);

        java.util.Comparator<TaskQueryRow> comparator = (r1, r2) -> {
            AiQueryTaskDto t1 = r1.dto();
            AiQueryTaskDto t2 = r2.dto();
            int comp = switch (sortField) {
                case "duedate" -> {
                    if (t1.dueDate() == null && t2.dueDate() == null)
                        yield 0;
                    if (t1.dueDate() == null)
                        yield 1;
                    if (t2.dueDate() == null)
                        yield -1;
                    yield t1.dueDate().compareTo(t2.dueDate());
                }
                case "priority" -> String.valueOf(t1.priority()).compareToIgnoreCase(String.valueOf(t2.priority()));
                case "difficultylevel" -> {
                    int d1 = t1.difficultyLevel() != null ? t1.difficultyLevel() : 0;
                    int d2 = t2.difficultyLevel() != null ? t2.difficultyLevel() : 0;
                    yield Integer.compare(d1, d2);
                }
                default -> String.valueOf(t1.title()).compareToIgnoreCase(String.valueOf(t2.title()));
            };
            return isAsc ? comp : -comp;
        };

        return rawTasks.stream()
                .map(task -> toQueryRow(task, fallbackZone))
                .filter(row -> row != null)
                .filter(row -> !Boolean.TRUE.equals(unassignedOnly) || row.dto().assigneeId() == null)
                .filter(row -> assigneeId == null || assigneeId.isBlank()
                        || assigneeId.equals(String.valueOf(row.dto().assigneeId())))
                .filter(row -> status == null || status.isBlank() || status.equalsIgnoreCase(row.dto().status()))
                .filter(row -> searchTerm == null || searchTerm.isBlank()
                        || (row.dto().title() != null
                                && row.dto().title().toLowerCase().contains(searchTerm.toLowerCase()))
                        || (row.dto().description() != null
                                && row.dto().description().toLowerCase().contains(searchTerm.toLowerCase())))
                .filter(row -> matchesDateFilter(row, dueToday, isOverdue))
                .sorted(comparator)
                .limit(limit != null ? Math.max(1, Math.min(limit, 50)) : 10)
                .map(TaskQueryRow::dto)
                .toList();
    }

    private boolean matchesDateFilter(TaskQueryRow row, Boolean dueToday, Boolean isOverdue) {
        if (!Boolean.TRUE.equals(dueToday) && !Boolean.TRUE.equals(isOverdue)) {
            return true;
        }
        String rawDue = row.dto().dueDate();
        if (rawDue == null || rawDue.isBlank()) {
            return false;
        }
        try {
            Instant dueAt = TaskDatePolicy.parseTaskDate(rawDue, row.projectTimezoneId(), TaskDateField.DUE);
            if (Boolean.TRUE.equals(dueToday)
                    && !TaskDatePolicy.isDueToday(dueAt, row.projectTimezoneId(), clock)) {
                return false;
            }
            if (Boolean.TRUE.equals(isOverdue)
                    && !TaskDatePolicy.isOverdue(dueAt, row.dto().status(), clock)) {
                return false;
            }
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private TaskQueryRow toQueryRow(Object task, String fallbackZone) {
        if (task instanceof TaskDetailDto d) {
            String zone = d.projectTimezoneId() != null ? d.projectTimezoneId() : fallbackZone;
            return new TaskQueryRow(new AiQueryTaskDto(
                    d.id(), d.projectId(), d.title(), d.status(), d.priority(), d.difficultyLevel(),
                    d.assigneeId(), d.assigneeName(), d.dueDate(), d.description(), d.version()), zone);
        }
        if (task instanceof TaskSummaryDto s) {
            String zone = s.projectTimezoneId() != null ? s.projectTimezoneId() : fallbackZone;
            return new TaskQueryRow(new AiQueryTaskDto(
                    s.id(), s.projectId(), s.title(), s.status(), s.priority(), s.difficultyLevel(),
                    s.assigneeId(), s.assigneeName(), s.dueDate(), s.description() != null ? s.description() : "",
                    s.version()), zone);
        }
        return null;
    }

    private String resolveProjectTimezone(Long projectId, Long userId) {
        if (projectId == null || projectInsightsPort == null) {
            return TaskDatePolicy.DEFAULT_PROJECT_TIMEZONE_ID;
        }
        try {
            return projectInsightsPort.getMyProjects(userId).stream()
                    .filter(p -> projectId.equals(p.projectId()) && p.timezoneId() != null && !p.timezoneId().isBlank())
                    .map(p -> p.timezoneId())
                    .findFirst()
                    .orElse(TaskDatePolicy.DEFAULT_PROJECT_TIMEZONE_ID);
        } catch (Exception ignored) {
            return TaskDatePolicy.DEFAULT_PROJECT_TIMEZONE_ID;
        }
    }

    private record TaskQueryRow(AiQueryTaskDto dto, String projectTimezoneId) {
    }

    @Tool("Fetch subtasks belonging to a specific parent task ID.")
    public Object getSubtasks(@P("The ID of the parent task") Long parentTaskId) {
        log.info("[AiTool] getSubtasks called for parent task {}", parentTaskId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getSubtasks(parentTaskId, userId);
    }

    @Tool("Update the status of a task (TODO, IN_PROGRESS). Requires expectedTaskVersion and confirmation.")
    public Object updateTaskStatus(
            @P("The ID of the task") Long taskId,
            @P("The new status (TODO, IN_PROGRESS)") String status,
            @P("The task version observed by caller (required)") Long expectedTaskVersion) {
        log.info("[AiTool] updateTaskStatus called for task {} (v={}) -> {}", taskId, expectedTaskVersion, status);
        requireExpectedTaskVersion(expectedTaskVersion);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        UUID requestId = UUID.randomUUID();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateTaskStatus",
                "Update task " + taskId + " status to " + status,
                args("taskId", taskId, "status", status, "expectedTaskVersion", expectedTaskVersion,
                        "requestId", requestId.toString()),
                null,
                () -> taskCommandPort.updateTaskStatus(taskId, status, expectedTaskVersion, requestId, userId));
    }

    @Tool("Update multiple fields of a task. Omit unchanged parameters. Requires expectedTaskVersion and confirmation.")
    public Object updateTask(
            @P("The ID of the task") Long taskId,
            @P("Optional title") String title,
            @P("Optional description") String description,
            @P("Optional status (TODO, IN_PROGRESS)") String status,
            @P("Optional priority (LOW, MEDIUM, HIGH, URGENT)") String priority,
            @P("Optional kanban position") Float position,
            @P("Optional full replacement label ID list") List<Long> labelIds,
            @P("Optional difficulty 1-10. Note: send as string like '5'") String difficultyLevel,
            @P("Optional full replacement required skill ID list") List<Long> requiredSkillIds,
            @P("Optional assignee user ID") Long assigneeId,
            @P("Optional start date as ISO-8601 instant or YYYY-MM-DD") String startDate,
            @P("Optional due date as ISO-8601 instant or YYYY-MM-DD") String dueDate,
            @P("The task version observed by caller (required)") Long expectedTaskVersion) {
        log.info("[AiTool] updateTask called for task {} (v={})", taskId, expectedTaskVersion);
        requireExpectedTaskVersion(expectedTaskVersion);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        UUID requestId = UUID.randomUUID();

        Integer parsedDifficultyLevel = null;
        if (difficultyLevel != null && !difficultyLevel.isBlank()) {
            try {
                parsedDifficultyLevel = Integer.valueOf(difficultyLevel.trim());
            } catch (Exception ignored) {
            }
        }
        final Integer finalDifficultyLevel = parsedDifficultyLevel;
        final List<Long> labelIdsAtPreview = labelIds == null ? null : List.copyOf(labelIds);
        final List<Long> requiredSkillIdsAtPreview = requiredSkillIds == null ? null : List.copyOf(requiredSkillIds);

        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateTask",
                "Update task " + taskId,
                args("taskId", taskId, "expectedTaskVersion", expectedTaskVersion, "requestId", requestId.toString(),
                        "title", title, "description", description, "status", status,
                        "priority", priority, "position", position, "labelIds", labelIdsAtPreview,
                        "difficultyLevel", finalDifficultyLevel, "requiredSkillIds", requiredSkillIdsAtPreview,
                        "assigneeId", assigneeId, "startDate", startDate, "dueDate", dueDate),
                null,
                () -> taskCommandPort.updateTask(taskId, title, description, status, priority, position,
                        labelIdsAtPreview, finalDifficultyLevel, requiredSkillIdsAtPreview,
                        assigneeId, startDate, dueDate, expectedTaskVersion, requestId, userId));
    }

    @Tool("Partially update a task. Send patchData map containing changed fields (title, description, status, priority, position, labelIds, difficultyLevel, requiredSkillIds, assigneeId, startDate, dueDate, sprintId). Use null for nullable fields to clear them. Requires expectedTaskVersion and confirmation.")
    public Object patchTask(
            @P("The ID of the task") Long taskId,
            @P("Map containing only changed fields; explicit null clears nullable fields") Object patchData,
            @P("The task version observed by caller (required)") Long expectedTaskVersion,
            @P("Optional reason for the change") String reason) {
        requireExpectedTaskVersion(expectedTaskVersion);
        Map<String, Object> patch = normalizePatch(patchData);
        log.info("[AiTool] patchTask called for task {} (v={}) with patch {}", taskId, expectedTaskVersion, patch);
        if (patch.isEmpty()) {
            return "patchTask requires at least one changed field. Use argument patchData with fields such as "
                    + "{\"dueDate\":\"2026-07-31\",\"assigneeId\":9}; do not create a confirmation for an empty patch.";
        }

        patch.keySet().forEach(fieldName -> validatePatchField(fieldName, PatchTaskCommand.ALLOWED_FIELDS));

        Map<String, Object> parsedValues = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object rawVal = entry.getValue();
            if (isExplicitNull(rawVal)) {
                if (PatchTaskCommand.NON_NULLABLE_FIELDS.contains(key)) {
                    throw new BusinessException(400, key + " cannot be null");
                }
                if (PatchTaskCommand.COLLECTION_FIELDS.contains(key)) {
                    throw new BusinessException(400, key + " cannot be null; omit to preserve or pass [] to clear");
                }
                parsedValues.put(key, null);
                continue;
            }
            Object typed = switch (key) {
                case "title", "description", "status", "priority", "startDate", "dueDate" -> rawVal.toString().trim();
                case "position" -> floatPatchValue(patch, key);
                case "difficultyLevel" -> integerPatchValue(patch, key);
                case "assigneeId", "sprintId" -> longPatchValue(patch, key);
                case "labelIds", "requiredSkillIds" -> {
                    List<Long> list = longListPatchValue(patch, key);
                    yield list == null ? List.of() : List.copyOf(list);
                }
                default -> throw new IllegalArgumentException("Unsupported patch field: " + key);
            };
            parsedValues.put(key, typed);
        }

        UUID requestId = UUID.randomUUID();
        PatchTaskCommand command = new PatchTaskCommand(
                taskId,
                expectedTaskVersion,
                requestId,
                Set.copyOf(parsedValues.keySet()),
                Collections.unmodifiableMap(parsedValues));

        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        Map<String, Object> frozenPatch = Collections.unmodifiableMap(new LinkedHashMap<>(parsedValues));

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("taskId", taskId);
        arguments.put("expectedTaskVersion", expectedTaskVersion);
        arguments.put("requestId", requestId.toString());
        arguments.put("patch", frozenPatch);
        if (reason != null) {
            arguments.put("reason", reason);
        }

        return pendingAiActionService.create(
                userId,
                sessionId,
                "patchTask",
                "Patch task " + taskId,
                arguments,
                arguments,
                () -> taskCommandPort.patchTask(command, userId));
    }

    @Tool("Delete a task by task ID. Requires expectedTaskVersion and confirmation.")
    public Object deleteTask(
            @P("The ID of the task to delete") Long taskId,
            @P("The task version observed by caller (required)") Long expectedTaskVersion) {
        log.info("[AiTool] deleteTask called for task {} (v={})", taskId, expectedTaskVersion);
        requireExpectedTaskVersion(expectedTaskVersion);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        UUID requestId = UUID.randomUUID();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteTask",
                "Delete task " + taskId,
                args("taskId", taskId, "expectedTaskVersion", expectedTaskVersion, "requestId", requestId.toString()),
                null,
                () -> {
                    taskCommandPort.deleteTask(taskId, expectedTaskVersion, requestId, userId);
                    return "Task deleted successfully";
                });
    }

    @Tool("Move a task on the kanban board by updating status and position. Requires expectedTaskVersion and confirmation.")
    public Object moveTaskKanban(
            @P("The ID of the task") String taskId,
            @P("Target status (TODO, IN_PROGRESS)") String status,
            @P("Target kanban position") Float position,
            @P("The task version observed by caller (required)") Long expectedTaskVersion) {
        log.info("[AiTool] moveTaskKanban called for task {} (v={}) -> {} @ {}",
                taskId, expectedTaskVersion, status, position);
        requireExpectedTaskVersion(expectedTaskVersion);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        Long resolvedTaskId = toLong(taskId);
        UUID requestId = UUID.randomUUID();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "moveTaskKanban",
                "Move task " + taskId + " to " + status,
                args("taskId", resolvedTaskId, "status", status, "position", position,
                        "expectedTaskVersion", expectedTaskVersion, "requestId", requestId.toString()),
                null,
                () -> taskCommandPort.moveTaskKanban(
                        resolvedTaskId, status, position, expectedTaskVersion, requestId, userId));
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
                    java.util.Map.of("name", "priority", "label", "Độ ưu tiên", "type", "select",
                            "options", java.util.List.of("LOW", "MEDIUM", "HIGH", "URGENT"), "required", true),
                    java.util.Map.of("name", "assigneeId", "label", "Người thực hiện", "type", "number"),
                    java.util.Map.of("name", "startDate", "label", "Ngày bắt đầu", "type", "date"),
                    java.util.Map.of("name", "dueDate", "label", "Hạn chót", "type", "date"),
                    java.util.Map.of("name", "sprintId", "label", "Sprint", "type", "number"),
                    java.util.Map.of("name", "difficultyLevel", "label", "Độ khó (1-10)", "type", "number",
                            "min", 1, "max", 10),
                    java.util.Map.of("name", "labelIds", "label", "Nhãn", "type", "multiselect"),
                    java.util.Map.of("name", "requiredSkillIds", "label", "Kỹ năng yêu cầu", "type", "multiselect")));
            return java.util.Map.of(
                    "status", "FORM_REQUIRED",
                    "form", java.util.Map.of(
                            "title", "Tạo task mới",
                            "intent", "createTask",
                            "fields", fields));
        }

        if (resolvedProjectId == null && resolvedParentId != null) {
            try {
                TaskDetailDto parentTask = taskCommandPort.getTaskDetails(resolvedParentId, userId);
                if (parentTask != null) {
                    resolvedProjectId = parentTask.projectId();
                    log.info("[AiTool] Resolved project ID {} from parent task {}", resolvedProjectId,
                            resolvedParentId);
                }
            } catch (Exception e) {
                log.warn("[AiTool] Failed to resolve project ID from parent task {}: {}", resolvedParentId,
                        e.getMessage());
            }
        }

        log.info("[AiTool] createTask called for project {} (parent={})", resolvedProjectId, resolvedParentId);

        Integer parsedDifficultyLevel = null;
        if (difficultyLevel != null && !difficultyLevel.isBlank()) {
            try {
                parsedDifficultyLevel = Integer.valueOf(difficultyLevel.trim());
            } catch (Exception ignored) {
            }
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
        final List<Long> resolvedLabelIdsAtPreview = resolvedLabelIds == null ? null : List.copyOf(resolvedLabelIds);
        final List<Long> resolvedRequiredSkillIdsAtPreview = resolvedRequiredSkillIds == null ? null
                : List.copyOf(resolvedRequiredSkillIds);
        final UUID requestId = UUID.randomUUID();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "createTask",
                "Create task \"" + title + "\" in project " + finalProjectId,
                args("projectId", finalProjectId, "title", title, "priority", priority,
                        "description", resolvedDescription, "sprintId", resolvedSprintId,
                        "difficultyLevel", finalDifficultyLevel, "labelIds", resolvedLabelIdsAtPreview,
                        "requiredSkillIds", resolvedRequiredSkillIdsAtPreview, "assigneeId", resolvedAssigneeId,
                        "startDate", resolvedStartDate, "dueDate", resolvedDueDate, "parentId", resolvedParentId,
                        "requestId", requestId.toString()),
                null,
                () -> taskCommandPort.createTask(finalProjectId, title, resolvedDescription, priority, null,
                        resolvedParentId, resolvedSprintId, finalDifficultyLevel, resolvedLabelIdsAtPreview,
                        resolvedRequiredSkillIdsAtPreview, resolvedAssigneeId, resolvedStartDate, resolvedDueDate,
                        requestId, userId));
    }

    @Tool("Submit an IN_PROGRESS task for review with summary and optional evidence project file IDs. Requires expectedTaskVersion and confirmation.")
    public Object submitTaskWork(
            @P("The ID of the task") Long taskId,
            @P("The task version observed by caller (required)") Long expectedTaskVersion,
            @P("Summary of completed work and verification") String summary,
            @P("Optional list of same-project file IDs as evidence") Object evidenceProjectFileIds) {
        log.info("[AiTool] submitTaskWork called for task {} (v={})", taskId, expectedTaskVersion);
        requireExpectedTaskVersion(expectedTaskVersion);
        TaskWorkflowCommandPort port = requireWorkflowPort();
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        List<Long> fileIds = toLongList(evidenceProjectFileIds);
        List<Long> frozenFileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
        UUID requestId = UUID.randomUUID();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "submitTaskWork",
                "Submit task " + taskId + " for review",
                args("taskId", taskId, "expectedTaskVersion", expectedTaskVersion, "summary", summary,
                        "evidenceProjectFileIds", frozenFileIds, "requestId", requestId.toString()),
                null,
                () -> {
                    CurrentActor actor = ToolExecutionContext.requireActor();
                    return port.submit(new SubmitTaskCommand(taskId, expectedTaskVersion, requestId, summary, frozenFileIds), actor);
                });
    }

    @Tool("Withdraw a pending submission on a REVIEW task before it is reviewed. Requires submissionId, expectedTaskVersion, and confirmation.")
    public Object withdrawTaskSubmission(
            @P("The ID of the task") Long taskId,
            @P("The ID of the current pending submission") Long submissionId,
            @P("The task version observed by caller (required)") Long expectedTaskVersion,
            @P("Optional reason for withdrawal") String reason) {
        log.info("[AiTool] withdrawTaskSubmission called for task {} sub={} (v={})", taskId, submissionId, expectedTaskVersion);
        requireExpectedTaskVersion(expectedTaskVersion);
        TaskWorkflowCommandPort port = requireWorkflowPort();
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        UUID requestId = UUID.randomUUID();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "withdrawTaskSubmission",
                "Withdraw submission " + submissionId + " on task " + taskId,
                args("taskId", taskId, "submissionId", submissionId, "expectedTaskVersion", expectedTaskVersion,
                        "reason", reason, "requestId", requestId.toString()),
                null,
                () -> {
                    CurrentActor actor = ToolExecutionContext.requireActor();
                    return port.withdrawSubmission(
                            new WithdrawTaskCommand(taskId, submissionId, expectedTaskVersion, requestId, reason), actor);
                });
    }

    @Tool("Review a submitted task (ACCEPT with quality-v1 rating 1-5, RETURN for revision, or SELF_ATTEST when sole active manager). Requires submissionId, expectedTaskVersion, and confirmation.")
    public Object reviewTaskSubmission(
            @P("The ID of the task") Long taskId,
            @P("The ID of the current pending submission") Long submissionId,
            @P("The task version observed by caller (required)") Long expectedTaskVersion,
            @P("Review decision: ACCEPT, RETURN, or SELF_ATTEST") String decision,
            @P("Quality rating 1-5 (required for ACCEPT, forbidden for RETURN and SELF_ATTEST)") Integer qualityRating,
            @P("Feedback / rationale / reason (required)") String feedback,
            @P("Anchor and evidence summary (required for ACCEPT)") String evidenceSummary) {
        log.info("[AiTool] reviewTaskSubmission called for task {} sub={} decision={} (v={})",
                taskId, submissionId, decision, expectedTaskVersion);
        requireExpectedTaskVersion(expectedTaskVersion);
        TaskWorkflowCommandPort port = requireWorkflowPort();
        ReviewDecision parsedDecision = parseReviewDecision(decision);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        UUID requestId = UUID.randomUUID();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "reviewTaskSubmission",
                "Review task " + taskId + " (" + parsedDecision.name() + ")",
                args("taskId", taskId, "submissionId", submissionId, "expectedTaskVersion", expectedTaskVersion,
                        "decision", parsedDecision.name(), "qualityRating", qualityRating,
                        "feedback", feedback, "evidenceSummary", evidenceSummary, "requestId", requestId.toString()),
                null,
                () -> {
                    CurrentActor actor = ToolExecutionContext.requireActor();
                    return port.review(
                            new ReviewTaskCommand(taskId, submissionId, expectedTaskVersion, requestId,
                                    parsedDecision, qualityRating, feedback, evidenceSummary),
                            actor);
                });
    }

    @Tool("Reopen a DONE task back to IN_PROGRESS with an audited reason. Requires expectedTaskVersion and confirmation.")
    public Object reopenTask(
            @P("The ID of the task") Long taskId,
            @P("The task version observed by caller (required)") Long expectedTaskVersion,
            @P("Reason for reopening the task (required)") String reason) {
        log.info("[AiTool] reopenTask called for task {} (v={})", taskId, expectedTaskVersion);
        requireExpectedTaskVersion(expectedTaskVersion);
        TaskWorkflowCommandPort port = requireWorkflowPort();
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        UUID requestId = UUID.randomUUID();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "reopenTask",
                "Reopen task " + taskId,
                args("taskId", taskId, "expectedTaskVersion", expectedTaskVersion, "reason", reason,
                        "requestId", requestId.toString()),
                null,
                () -> {
                    CurrentActor actor = ToolExecutionContext.requireActor();
                    return port.reopen(new ReopenTaskCommand(taskId, expectedTaskVersion, requestId, reason), actor);
                });
    }

    @Tool("Supersede a current task evaluation on a DONE task with a new independent quality-v1 rating (1-5). Requires exact submission, evaluation ID/version, task version, and confirmation.")
    public Object supersedeTaskEvaluation(
            @P("The ID of the task") Long taskId,
            @P("The ID of the accepted submission") Long submissionId,
            @P("The current evaluation ID to supersede") Long expectedEvaluationId,
            @P("The current evaluation version to supersede") Long expectedEvaluationVersion,
            @P("The task version observed by caller (required)") Long expectedTaskVersion,
            @P("New quality-v1 rating 1-5") Integer qualityRating,
            @P("Reason for supersession/correction (required)") String reason,
            @P("Anchor and evidence summary (required)") String evidenceSummary) {
        log.info("[AiTool] supersedeTaskEvaluation called for task {} eval={} v={} (taskV={})",
                taskId, expectedEvaluationId, expectedEvaluationVersion, expectedTaskVersion);
        requireExpectedTaskVersion(expectedTaskVersion);
        TaskWorkflowCommandPort port = requireWorkflowPort();
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        UUID requestId = UUID.randomUUID();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "supersedeTaskEvaluation",
                "Supersede evaluation " + expectedEvaluationId + " on task " + taskId,
                args("taskId", taskId, "submissionId", submissionId, "expectedEvaluationId", expectedEvaluationId,
                        "expectedEvaluationVersion", expectedEvaluationVersion,
                        "expectedTaskVersion", expectedTaskVersion, "qualityRating", qualityRating,
                        "reason", reason, "evidenceSummary", evidenceSummary, "requestId", requestId.toString()),
                null,
                () -> {
                    CurrentActor actor = ToolExecutionContext.requireActor();
                    return port.supersedeEvaluation(
                            new SupersedeEvaluationCommand(taskId, submissionId, expectedEvaluationId,
                                    expectedEvaluationVersion, expectedTaskVersion, requestId,
                                    qualityRating, reason, evidenceSummary),
                            actor);
                });
    }

    @Tool("Create a linked follow-up/successor task for a completed or closed-sprint task without mutating historical records. Requires confirmation.")
    public Object createSuccessorTask(
            @P("The source task ID to create a follow-up successor for") Long sourceTaskId,
            @P("Optional title for the follow-up task") String title,
            @P("Optional description for the follow-up task") String description,
            @P("Optional priority (LOW, MEDIUM, HIGH, URGENT)") String priority,
            @P("Optional writable target sprint ID (null for backlog)") Long sprintId,
            @P("Optional difficulty 1-10") Integer difficultyLevel,
            @P("Optional assignee user ID") Long assigneeId,
            @P("Optional start date (YYYY-MM-DD or ISO-8601)") String startDate,
            @P("Optional due date (YYYY-MM-DD or ISO-8601)") String dueDate) {
        log.info("[AiTool] createSuccessorTask called for sourceTask {}", sourceTaskId);
        if (sourceTaskId == null) {
            throw new BusinessException(400, "sourceTaskId is required");
        }
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        UUID requestId = UUID.randomUUID();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("sourceTaskId", sourceTaskId);
        arguments.put("title", title);
        arguments.put("description", description);
        arguments.put("priority", priority);
        arguments.put("sprintId", sprintId);
        arguments.put("difficultyLevel", difficultyLevel);
        arguments.put("assigneeId", assigneeId);
        arguments.put("startDate", startDate);
        arguments.put("dueDate", dueDate);
        arguments.put("requestId", requestId.toString());

        return pendingAiActionService.create(
                userId,
                sessionId,
                "createSuccessorTask",
                "Create successor task for task " + sourceTaskId,
                arguments,
                null,
                () -> taskCommandPort.createSuccessorTask(sourceTaskId, title, description, priority, sprintId,
                        difficultyLevel, assigneeId, startDate, dueDate, requestId, userId));
    }

    private TaskWorkflowCommandPort requireWorkflowPort() {
        if (taskWorkflowCommandPort == null) {
            throw new BusinessException(503, "Task workflow command port is unavailable");
        }
        return taskWorkflowCommandPort;
    }

    private static ReviewDecision parseReviewDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, "decision is required (ACCEPT, RETURN, or SELF_ATTEST)");
        }
        try {
            return ReviewDecision.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "Invalid review decision. Use ACCEPT, RETURN, or SELF_ATTEST");
        }
    }

    private static void requireExpectedTaskVersion(Long expectedTaskVersion) {
        if (expectedTaskVersion == null) {
            throw new BusinessException(428,
                    "expectedTaskVersion is required; call getTaskDetails first to obtain the current task version");
        }
    }

    private static boolean isExplicitNull(Object rawVal) {
        if (rawVal == null) {
            return true;
        }
        if (rawVal instanceof String s) {
            String t = s.trim();
            return "null".equalsIgnoreCase(t) || "<null>".equalsIgnoreCase(t) || "undefined".equalsIgnoreCase(t);
        }
        return false;
    }
}
