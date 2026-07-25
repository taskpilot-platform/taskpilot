package com.taskpilot.ai.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.dto.RecommendAndAssignResult;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.service.SmartQueryService;
import com.taskpilot.contracts.assignment.dto.ProjectDueDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.aiquery.dto.*;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import com.taskpilot.contracts.aiquery.port.out.SprintQueryPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommentQueryPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.contracts.user.port.out.UserNotificationQueryPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPilotAiTools {

    private static final ObjectMapper PATCH_OBJECT_MAPPER = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .build();

    private final AutoAssignmentService autoAssignmentService;
    private final ProjectMemberPort projectMemberPort;
    private final ProjectInsightsPort projectInsightsPort;
    private final MemberAnalyticsPort memberAnalyticsPort;
    private final TaskCommandPort taskCommandPort;
    private final TaskCommentQueryPort taskCommentQueryPort;
    private final SprintQueryPort sprintQueryPort;
    private final SkillPort skillPort;
    private final UserNotificationQueryPort userNotificationQueryPort;
    private final PendingAiActionService pendingAiActionService;
    private final SmartQueryService smartQueryService;
    @jakarta.annotation.Nullable
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public TaskPilotAiTools(
            AutoAssignmentService autoAssignmentService,
            ProjectMemberPort projectMemberPort,
            ProjectInsightsPort projectInsightsPort,
            MemberAnalyticsPort memberAnalyticsPort,
            TaskCommandPort taskCommandPort,
            TaskCommentQueryPort taskCommentQueryPort,
            SprintQueryPort sprintQueryPort,
            SkillPort skillPort,
            UserNotificationQueryPort userNotificationQueryPort,
            PendingAiActionService pendingAiActionService,
            SmartQueryService smartQueryService) {
        this(autoAssignmentService, projectMemberPort, projectInsightsPort, memberAnalyticsPort,
                taskCommandPort, taskCommentQueryPort, sprintQueryPort, skillPort, userNotificationQueryPort,
                pendingAiActionService, smartQueryService, null);
    }

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

    @Tool("Get the workload snapshot of all members in a project by project ID.")
    public List<MemberWorkloadDto> getMemberWorkload(@P("The ID of the project") String projectId) {
        log.info("[AiTool] getMemberWorkload called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return memberAnalyticsPort.getMemberWorkloadForProject(toLong(projectId), userId);
    }

    @Tool("Search and list members in a specific project. All filters except projectId are optional. Supports sorting.")
    public Object queryProjectMembers(
            @P("The ID of the project") String projectId,
            @P("Optional. Role to filter by (e.g. MANAGER, MEMBER). Can be null/empty.") String role,
            @P("Optional. Search keyword for member name") String searchTerm,
            @P("Optional. Field to sort by: 'fullName', 'role' (default 'fullName')") String sortBy,
            @P("Optional. Sort direction: 'ASC' or 'DESC' (default 'ASC')") String sortDirection,
            @P("Optional. Maximum number of members to return (default 10, max 20)") Integer limit) {
        log.info("[AiTool] queryProjectMembers called for project {} role={} search={} sortBy={} sortDir={}", projectId, role, searchTerm, sortBy, sortDirection);
        Long userId = ToolExecutionContext.requireUserId();
        List<ProjectMemberDto> allMembers = projectInsightsPort.getProjectMembers(toLong(projectId), userId);

        String sortField = sortBy != null ? sortBy.trim().toLowerCase() : "fullname";
        String direction = sortDirection != null ? sortDirection.trim().toUpperCase() : "ASC";
        boolean isAsc = !"DESC".equals(direction);

        java.util.Comparator<ProjectMemberDto> comparator = (m1, m2) -> {
            int comp = 0;
            switch (sortField) {
                case "role":
                    comp = String.valueOf(m1.role()).compareToIgnoreCase(String.valueOf(m2.role()));
                    break;
                case "fullname":
                default:
                    comp = String.valueOf(m1.fullName()).compareToIgnoreCase(String.valueOf(m2.fullName()));
                    break;
            }
            return isAsc ? comp : -comp;
        };

        // Apply filters in-memory for Stage 1
        List<Map<String, Object>> filtered = allMembers.stream()
                .filter(m -> role == null || role.isBlank() || role.equalsIgnoreCase(m.role()))
                .filter(m -> searchTerm == null || searchTerm.isBlank() || 
                        (m.fullName() != null && m.fullName().toLowerCase().contains(searchTerm.toLowerCase())))
                .sorted(comparator)
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("memberId", m.memberId());
                    map.put("fullName", m.fullName());
                    map.put("role", m.role());
                    map.put("skills", m.skills() != null ? m.skills() : "");
                    return map;
                })
                .limit(limit != null ? Math.max(1, Math.min(limit, 20)) : 10)
                .collect(Collectors.toList());

        return Map.of("results", filtered, "totalMatched", filtered.size());
    }

    @Tool("Fetch all labels configured for a project by project ID.")
    public List<LabelSummaryDto> getProjectLabels(@P("The ID of the project") String projectId) {
        log.info("[AiTool] getProjectLabels called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return projectInsightsPort.getProjectLabels(toLong(projectId), userId);
    }

    @Tool("Get workload details of a specific member by member ID (open tasks, overdue tasks, estimated hours).")
    public MemberWorkloadDto getMemberWorkloadByMemberId(@P("The ID of the member") String memberId) {
        log.info("[AiTool] getMemberWorkloadByMemberId called for member {}", memberId);
        Long userId = ToolExecutionContext.requireUserId();
        return memberAnalyticsPort.getMemberWorkload(toLong(memberId), userId);
    }

    @Tool("Get task details (title, description, status, priority, difficulty, required skills, due date) by task ID.")
    public TaskDetailDto getTaskDetails(@P("The ID of the task") String taskId) {
        log.info("[AiTool] getTaskDetails called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getTaskDetails(toLong(taskId), userId);
    }

    @Tool("Search the global system skill directory by keyword (use empty string to list default active skills).")
    public List<SkillDto> searchSystemSkills(
            @P("Skill search keyword. Use empty string to list common active skills.") String keyword) {
        String safeKeyword = keyword == null ? "" : keyword.trim();
        log.info("[AiTool] searchSystemSkills called keyword='{}'", safeKeyword);
        return skillPort.search(safeKeyword);
    }

    @Tool("Create a new system skill in the shared skill directory. Requires confirmation.")
    public Object createSystemSkill(
            @P("Skill name") String name,
            @P("Optional skill description") String description) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] createSystemSkill called name={}", name);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "createSystemSkill",
                "Create system skill \"" + name + "\"",
                args("name", name, "description", description),
                args("name", name, "description", description),
                () -> skillPort.createSystemSkill(name, description, userId));
    }

    @Tool("Partially update a system skill. Send patchData map containing changed fields (name, description). Requires confirmation.")
    public Object patchSystemSkill(
            @P("The ID of the skill") Long skillId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        Map<String, Object> patch = normalizePatch(patchData);
        log.info("[AiTool] patchSystemSkill called for skill {} patch {}", skillId, patch);
        patch.keySet().forEach(fieldName -> validatePatchField(fieldName, Set.of("name", "description")));
        String name = stringPatchValue(patch, "name");
        String description = stringPatchValue(patch, "description");
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "patchSystemSkill",
                "Patch system skill " + skillId,
                args("skillId", skillId, "patch", patch, "reason", reason),
                args("skillId", skillId, "patch", patch, "reason", reason),
                () -> skillPort.patchSystemSkill(skillId, name, description, userId));
    }

    @Tool("Delete or deactivate a system skill in the shared directory by skill ID. Requires confirmation.")
    public Object deleteSystemSkill(@P("System skill ID to deactivate") Long skillId) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] deleteSystemSkill called for skill {}", skillId);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteSystemSkill",
                "Delete system skill " + skillId,
                args("skillId", skillId),
                null,
                () -> {
                    skillPort.deleteSystemSkill(skillId, userId);
                    return "System skill deleted successfully";
                });
    }

    @Tool("List the current user's personal skills.")
    public Object getMySkills() {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] getMySkills called for user {}", userId);
        return skillPort.getMySkills(userId);
    }

    @Tool("Add a system skill that does NOT exist in the current user's personal skills with a level (1-5). CRITICAL: If the user already has this skill and you want to update or change its level, you MUST call patchMySkill instead! If adding common skills like Java, use the ID mentioned in parameter descriptions (e.g. 1 for Java) directly! DO NOT call searchSystemSkills to look up ID. Requires confirmation.")
    public Object addMySkill(
            @P("System skill ID (e.g. 1 for Java)") Long skillId,
            @P("Skill level from 1 to 5") Integer level) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] addMySkill called for skill {} level {}", skillId, level);
        int safeLevel = clampSkillLevel(level);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "addMySkill",
                "Add skill " + skillId + " at level " + safeLevel,
                args("skillId", skillId, "level", safeLevel),
                args("skillId", skillId, "level", safeLevel),
                () -> skillPort.addMySkill(skillId, safeLevel, userId));
    }

    @Tool("Partially update an existing personal skill (e.g. update its level). Send patchData map containing changed fields (level 1-5). CRITICAL: If you want to update or change the level of a skill the user already has, you MUST call this tool (patchMySkill) instead of addMySkill! Requires confirmation.")
    public Object patchMySkill(
            @P("The ID of the skill (e.g. 1 for Java)") Long skillId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        Map<String, Object> patch = normalizePatch(patchData);
        log.info("[AiTool] patchMySkill called for skill {} patch {}", skillId, patch);
        patch.keySet().forEach(fieldName -> validatePatchField(fieldName, Set.of("level")));
        Integer level = integerPatchValue(patch, "level");
        if (level == null) {
            throw new IllegalArgumentException("patchMySkill requires level in patch.");
        }
        int safeLevel = clampSkillLevel(level);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        // note: AI doesn't know context skill ID usually, so this is mostly if AI knows the user's skill mapping.
        return pendingAiActionService.create(
                userId,
                sessionId,
                "patchMySkill",
                "Patch my skill " + skillId,
                args("skillId", skillId, "patch", args("level", safeLevel), "reason", reason),
                args("skillId", skillId, "patch", args("level", safeLevel), "reason", reason),
                () -> skillPort.updateMySkill(skillId, safeLevel, userId));
    }

    @Tool("Remove a skill from the current user's personal skills. Requires confirmation.")
    public Object deleteMySkill(@P("System skill ID to remove from my skills (e.g. 1 for Java)") Long skillId) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] deleteMySkill called for skill {}", skillId);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteMySkill",
                "Delete my skill " + skillId,
                args("skillId", skillId),
                null,
                () -> {
                    skillPort.deleteMySkill(skillId, userId);
                    return "Skill removed successfully";
                });
    }

    @Tool("List notifications for the current user. Set unreadOnly=true to get unread notifications only.")
    public String getMyNotifications(
            @P("Return only unread notifications when true") Boolean unreadOnly,
            @P("Maximum number of notifications to return, between 1 and 50") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        boolean onlyUnread = Boolean.TRUE.equals(unreadOnly);
        log.info("[AiTool] getMyNotifications called for user {} unreadOnly={} limit={}",
                userId, onlyUnread, safeLimit);
        try {
            return PATCH_OBJECT_MAPPER.writeValueAsString(
                userNotificationQueryPort.getMyNotifications(userId, onlyUnread, safeLimit)
            );
        } catch (Exception e) {
            log.error("[AiTool] Failed to serialize notifications", e);
            return "[]";
        }
    }

    @Tool("Get the count of unread notifications for the current user.")
    public Object getUnreadNotificationCount() {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] getUnreadNotificationCount called for user {}", userId);
        return Map.of("unreadCount", userNotificationQueryPort.getUnreadNotificationCount(userId));
    }

    @Tool("Mark a specific notification as read by its ID. Requires confirmation.")
    public Object markNotificationRead(@P("The ID of the notification to mark as read") Long notificationId) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] markNotificationRead called for notification {}", notificationId);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "markNotificationRead",
                "Mark notification " + notificationId + " as read",
                args("notificationId", notificationId),
                args("notificationId", notificationId),
                () -> userNotificationQueryPort.markNotificationRead(notificationId, userId));
    }

    @Tool("Mark all notifications for the current user as read. Requires confirmation.")
    public Object markAllNotificationsRead() {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] markAllNotificationsRead called for user {}", userId);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "markAllNotificationsRead",
                "Mark all notifications as read",
                args(),
                null,
                () -> Map.of("updatedCount", userNotificationQueryPort.markAllNotificationsRead(userId)));
    }

    @Tool("Assign a task to a project member by task ID and member ID. Requires confirmation.")
    public Object assignTaskToMember(
            @P("The ID of the task") String taskId,
            @P("The ID of the member") String memberId,
            @P("Reason for the assignment") String reason) {
        log.info("[AiTool] assignTaskToMember called for task {} -> member {}", taskId, memberId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        String safeReason = hasText(reason) ? reason : "Task assigned by AI tool";

        Long resolvedTaskId = toLong(taskId);
        Long resolvedMemberId = toLong(memberId);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "assignTaskToMember",
                "Assign task " + taskId + " to member " + memberId,
                args("taskId", resolvedTaskId, "memberId", resolvedMemberId, "reason", safeReason),
                null,
                () -> taskCommandPort.assignTaskToMember(resolvedTaskId, resolvedMemberId, safeReason, userId, false));
    }

    @Tool("Assign a task to a project member by task ID and member name. Resolves project and member. Requires confirmation.")
    public Object assignTaskToMemberByName(
            @P("The ID of the task") String taskId,
            @P("Full or partial member name, e.g. Julia Design") String memberName,
            @P("Reason for the assignment") String reason) {
        log.info("[AiTool] assignTaskToMemberByName called for task {} -> {}", taskId, memberName);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        Long resolvedTaskId = toLong(taskId);
        TaskDetailDto task = taskCommandPort.getTaskDetails(resolvedTaskId, userId);
        ProjectMemberDto member = resolveProjectMemberByName(task.projectId(), memberName, userId);
        String safeReason = hasText(reason)
                ? reason
                : "User explicitly requested assignment to " + member.fullName() + ".";

        return pendingAiActionService.create(
                userId,
                sessionId,
                "assignTaskToMember",
                "Assign task " + taskId + " to " + member.fullName() + " (user specified)",
                args("taskId", resolvedTaskId, "memberId", member.memberId(), "memberName", member.fullName(),
                        "reason", safeReason, "source", "user_specified_assignee"),
                Map.of("taskId", resolvedTaskId, "memberId", member.memberId(), "memberName", member.fullName(),
                        "projectId", task.projectId(), "reason", safeReason),
                () -> taskCommandPort.assignTaskToMember(resolvedTaskId, member.memberId(), safeReason, userId, false));
    }

    @Tool("Recommend the top candidate and assign the task to them in a single write operation. Requires confirmation.")
    public Object recommendAndAssignTask(
            @P("The ID of the task to assign") String taskId,
            @P("Optional project ID. If omitted, it is read from task details") String projectId,
            @P("Optional comma-separated required skill names. If omitted, task required skills are used") String skills,
            @P("Optional task difficulty 1-10. If omitted, task difficulty is used. Note: send as string like '5'") String difficulty,
            @P("Reason to store with the assignment") String reason) {
        log.info("[AiTool] recommendAndAssignTask called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        Long resolvedTaskId = toLong(taskId);
        TaskDetailDto task = taskCommandPort.getTaskDetails(resolvedTaskId, userId);
        Long resolvedProjectId = hasText(projectId) ? toLong(projectId) : task.projectId();
        String resolvedSkills = hasText(skills) ? skills : task.requiredSkills();
        boolean shouldPersistProvidedSkills = hasText(skills) && !hasText(task.requiredSkills());

        if (!hasText(resolvedSkills)) {
            return new RecommendAndAssignResult(false, resolvedTaskId, resolvedProjectId, null, null, reason,
                    null, null,
                    "Task " + taskId + " is missing required skills. Please provide skills before assigning.");
        }

        int parsedDifficulty = task.difficultyLevel() != null ? task.difficultyLevel() : 5;
        if (difficulty != null && !difficulty.isBlank()) {
            try { parsedDifficulty = Integer.parseInt(difficulty.trim()); } catch (Exception ignored) {}
        }
        int resolvedDifficulty = Math.max(1, Math.min(10, parsedDifficulty));

        AutoAssignmentResponse recommendation = autoAssignmentService.recommendCandidates(
                resolvedProjectId,
                parseSkills(resolvedSkills),
                resolvedDifficulty,
                userId);

        if (recommendation.candidates() == null || recommendation.candidates().isEmpty()) {
            return new RecommendAndAssignResult(false, resolvedTaskId, resolvedProjectId, null, null, reason,
                    recommendation, null,
                    "No eligible candidate found for task " + taskId + ".");
        }

        CandidateScore selected = recommendation.candidates().get(0);
        String safeReason = hasText(reason)
                ? reason
                : "AI selected the top-ranked candidate based on skill fit, workload, and project heuristic mode.";
        RecommendAndAssignResult preview = new RecommendAndAssignResult(false, resolvedTaskId, resolvedProjectId,
                selected.getUserId(), selected.getFullName(), safeReason, recommendation, null,
                "Ready to assign task " + taskId + " to " + selected.getFullName() + " after confirmation.");

        return pendingAiActionService.create(
                userId,
                sessionId,
                "recommendAndAssignTask",
                (shouldPersistProvidedSkills ? "Save required skills and assign task " : "Assign task ")
                        + taskId + " to " + selected.getFullName() + " (top recommended candidate)",
                args("taskId", resolvedTaskId, "projectId", resolvedProjectId, "skills", resolvedSkills,
                        "difficulty", resolvedDifficulty, "memberId", selected.getUserId(), "reason", safeReason,
                        "persistSkills", shouldPersistProvidedSkills),
                preview,
                () -> {
                    if (shouldPersistProvidedSkills) {
                        taskCommandPort.updateTaskRequiredSkills(resolvedTaskId, resolvedSkills, userId);
                    }
                    TaskAssignmentResultDto assignment = taskCommandPort.assignTaskToMember(
                            resolvedTaskId,
                            selected.getUserId(),
                            safeReason,
                            userId,
                            false);
                    return new RecommendAndAssignResult(true, resolvedTaskId, resolvedProjectId, selected.getUserId(),
                            selected.getFullName(), safeReason, recommendation, assignment,
                            "Task " + taskId + " assigned to " + selected.getFullName() + ".");
                });
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



    @Tool("Recommend ranked candidates for a project based on skills and difficulty (1-10, default is 5). Read-only.")
    public AutoAssignmentResponse recommendAssignmentCandidates(
            @P("The project ID") String projectId,
            @P("Comma-separated list of required skill names") String skills,
            @P("Task difficulty 1-10. Note: send as string like '5'") String difficulty) {
        log.info("[AiTool] recommendAssignmentCandidates called for project {}", projectId);

        Long userId = ToolExecutionContext.requireUserId();
        int parsedDifficulty = 5;
        if (difficulty != null && !difficulty.isBlank()) {
            try { parsedDifficulty = Integer.parseInt(difficulty.trim()); } catch (Exception ignored) {}
        }
        int safeDifficulty = Math.max(1, Math.min(10, parsedDifficulty));
        List<String> requiredSkills = parseSkills(skills);

        return autoAssignmentService.recommendCandidates(toLong(projectId), requiredSkills, safeDifficulty, userId);
    }

    @Tool("Recommend and compare candidates specifically for a task ID, reading its metrics automatically. Supports filters. Read-only.")
    public AutoAssignmentResponse recommendTaskAssignmentCandidates(
            @P("The ID of the task") String taskId,
            @P("Optional comma-separated required skill names or IDs. Use this when the task is missing skills and the user provided them in a form.") String skills,
            @P("Optional task difficulty 1-10. If omitted, task difficulty is used. Note: send as string like '5'") String difficulty,
            @P("Optional comma-separated member names to compare/include, e.g. 'Julia Design, Evan Ops'") String includeMemberNames,
            @P("Optional comma-separated member/user IDs to compare/include, e.g. '10,5'") String includeMemberIds,
            @P("Optional comma-separated member names to exclude") String excludeMemberNames,
            @P("Optional comma-separated member/user IDs to exclude") String excludeMemberIds,
            @P("Set true to exclude the task's current assignee") String excludeCurrentAssignee) {
        Long userId = ToolExecutionContext.requireUserId();
        Long resolvedTaskId = toLong(taskId);
        TaskDetailDto task = taskCommandPort.getTaskDetails(resolvedTaskId, userId);
        Long projectId = task.projectId();
        String resolvedSkills = hasText(skills) ? skills : task.requiredSkills();
        if (!hasText(resolvedSkills)) {
            resolvedSkills = "Java";
        }

        Set<Long> includeIds = parseIdSet(includeMemberIds);
        includeIds.addAll(resolveProjectMemberIdsByNames(projectId, includeMemberNames, userId));

        Set<Long> excludeIds = parseIdSet(excludeMemberIds);
        excludeIds.addAll(resolveProjectMemberIdsByNames(projectId, excludeMemberNames, userId));
        if (isTruthy(excludeCurrentAssignee) && task.assigneeId() != null) {
            excludeIds.add(task.assigneeId());
        }

        int parsedDifficulty = task.difficultyLevel() == null ? 5 : task.difficultyLevel();
        if (hasText(difficulty)) {
            try { parsedDifficulty = Integer.parseInt(difficulty.trim()); } catch (Exception ignored) {}
        }
        int resolvedDifficulty = Math.max(1, Math.min(10, parsedDifficulty));
        AutoAssignmentResponse response = autoAssignmentService.recommendCandidates(
                projectId,
                parseSkills(resolvedSkills),
                resolvedDifficulty,
                userId,
                includeIds,
                excludeIds);

        String explanation = response.aiExplanation();
        if ((explanation == null || explanation.isBlank()) && isTruthy(excludeCurrentAssignee) && task.assigneeName() != null) {
            explanation = "Excluded current assignee " + task.assigneeName() + " for this recommendation.";
        }
        return AutoAssignmentResponse.builder()
                .projectId(response.projectId())
                .requiredSkills(response.requiredSkills())
                .candidates(response.candidates())
                .aiExplanation(explanation)
                .build();
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

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s) || "<null>".equalsIgnoreCase(s) || "undefined".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            log.warn("[AiTool] Failed to parse Long from: {}", s);
            return null;
        }
    }

    private List<Long> toLongList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::toLong)
                    .filter(item -> item != null)
                    .toList();
        }
        String s = value.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s) || "<null>".equalsIgnoreCase(s) || "undefined".equalsIgnoreCase(s)) {
            return null;
        }
        return Arrays.stream(s.split(","))
                .map(this::toLong)
                .filter(item -> item != null)
                .toList();
    }

    private List<String> parseSkills(String skills) {
        if (skills == null || skills.isBlank()) {
            return List.of();
        }
        return Arrays.stream(skills.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    private Set<Long> parseIdSet(String ids) {
        if (!hasText(ids)) {
            return new java.util.LinkedHashSet<>();
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(this::hasText)
                .map(this::toLong)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private Set<Long> resolveProjectMemberIdsByNames(Long projectId, String memberNames, Long userId) {
        if (!hasText(memberNames)) {
            return new java.util.LinkedHashSet<>();
        }
        return Arrays.stream(memberNames.split(","))
                .map(String::trim)
                .filter(this::hasText)
                .map(name -> resolveProjectMemberByName(projectId, name, userId).memberId())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private boolean isTruthy(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = normalizeName(value);
        return Set.of("true", "yes", "y", "1", "co", "ok", "exclude", "loai").contains(normalized);
    }

    private ProjectMemberDto resolveProjectMemberByName(Long projectId, String memberName, Long userId) {
        if (!hasText(memberName)) {
            throw new IllegalArgumentException("Member name is required");
        }
        String target = normalizeName(memberName);
        List<ProjectMemberDto> members = projectInsightsPort.getProjectMembers(projectId, userId);
        return members.stream()
                .filter(member -> normalizeName(member.fullName()).equals(target))
                .findFirst()
                .or(() -> members.stream()
                        .filter(member -> normalizeName(member.fullName()).contains(target)
                                || target.contains(normalizeName(member.fullName())))
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No project member matched name '" + memberName + "' in project " + projectId));
    }

    private String normalizeName(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT)
                .replace('\u0111', 'd')
                .replace('\u0110', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // =========================================================================
    // Project/task CRUD tools backed by the real project data ports.
    // =========================================================================

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

    @Tool("Fetch subtasks belonging to a specific parent task ID.")
    public Object getSubtasks(@P("The ID of the parent task") Long parentTaskId) {
        log.info("[AiTool] getSubtasks called for parent task {}", parentTaskId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getSubtasks(parentTaskId, userId);
    }

    @Tool("Fetch comments made on a specific task by task ID.")
    public String getTaskComments(
            @P("The ID of the task") Long taskId,
            @P("Optional. Maximum number of comments to return. Default 10, max 30.") Integer limit) {
        log.info("[AiTool] getTaskComments called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        List<com.taskpilot.contracts.aiquery.dto.TaskCommentSummaryDto> comments = taskCommentQueryPort.getTaskComments(taskId, userId);
        List<com.taskpilot.contracts.aiquery.dto.TaskCommentSummaryDto> limited = comments.stream()
                .limit(limit != null ? Math.max(1, Math.min(limit, 30)) : 10)
                .collect(Collectors.toList());
        try {
            return PATCH_OBJECT_MAPPER.writeValueAsString(limited);
        } catch (Exception e) {
            log.error("[AiTool] Failed to serialize comments", e);
            return "[]";
        }
    }

    @Tool("Fetch comments authored by or mentioning the current user, across projects/tasks. Set mentionedMe=true for mentions.")
    public String getMyTaskComments(
            @P("Optional project ID filter") Long projectId,
            @P("Optional task ID filter") Long taskId,
            @P("True when the user asks for comments mentioning them") Boolean mentionedMe,
            @P("Maximum number of comments to return, between 1 and 50") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        boolean onlyMentioned = Boolean.TRUE.equals(mentionedMe);
        Long resolvedProjectId = (projectId != null && projectId > 0) ? projectId : null;
        Long resolvedTaskId = (taskId != null && taskId > 0) ? taskId : null;
        log.info("[AiTool] getMyTaskComments called for user {} project={} task={} mentionedMe={} limit={}",
                userId, resolvedProjectId, resolvedTaskId, onlyMentioned, safeLimit);
        try {
            return PATCH_OBJECT_MAPPER.writeValueAsString(
                taskCommentQueryPort.getMyTaskComments(resolvedProjectId, resolvedTaskId, onlyMentioned, safeLimit, userId)
            );
        } catch (Exception e) {
            log.error("[AiTool] Failed to serialize comments", e);
            return "[]";
        }
    }

    @Tool("Create a comment or reply to a comment on a task. Requires confirmation.")
    public Object createTaskComment(
            @P("The ID of the task") Long taskId,
            @P("Comment content") String content,
            @P("Optional parent comment ID when replying") Long parentCommentId,
            @P("Optional mentioned user IDs") List<Long> mentionedUserIds) {
        log.info("[AiTool] createTaskComment called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        Long resolvedParentCommentId = (parentCommentId != null && parentCommentId > 0) ? parentCommentId : null;
        return pendingAiActionService.create(
                userId,
                sessionId,
                "createTaskComment",
                "Create comment on task " + taskId,
                args("taskId", taskId, "content", content, "parentCommentId", resolvedParentCommentId,
                        "mentionedUserIds", mentionedUserIds),
                null,
                () -> taskCommentQueryPort.createTaskComment(taskId, content, resolvedParentCommentId, mentionedUserIds,
                        userId));
    }

    @Tool("Update the content of an existing task comment authored by the current user. Requires confirmation.")
    public Object updateTaskComment(
            @P("The ID of the task") Long taskId,
            @P("The ID of the comment") Long commentId,
            @P("Updated comment content") String content,
            @P("Optional mentioned user IDs") List<Long> mentionedUserIds) {
        log.info("[AiTool] updateTaskComment called for task {} comment {}", taskId, commentId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateTaskComment",
                "Update comment " + commentId + " on task " + taskId,
                args("taskId", taskId, "commentId", commentId, "content", content,
                        "mentionedUserIds", mentionedUserIds),
                null,
                () -> taskCommentQueryPort.updateTaskComment(taskId, commentId, content, mentionedUserIds,
                        userId));
    }

    @Tool("Partially update a task comment. Send patchData map containing changed fields (content, mentionedUserIds). Requires confirmation.")
    public Object patchTaskComment(
            @Nullable @P("Optional. The ID of the task. If not provided, the system will resolve it automatically.") Long taskId,
            @P("The ID of the comment to patch") Long commentId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        Map<String, Object> patch = normalizePatch(patchData);
        log.info("[AiTool] patchTaskComment called for task {} comment {} with patch {}",
                taskId, commentId, patch);
        patch.keySet().forEach(fieldName -> validatePatchField(fieldName, Set.of("content", "mentionedUserIds")));
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        if (taskId == null && jdbcTemplate != null) {
            try {
                taskId = jdbcTemplate.queryForObject(
                        "select task_id from comments where id = ?",
                        Long.class,
                        commentId
                );
                log.info("[AiTool] Resolved taskId {} for commentId {} from database", taskId, commentId);
            } catch (Exception e) {
                log.warn("[AiTool] Failed to resolve taskId for commentId {} via JDBC: {}", commentId, e.getMessage());
            }
        }
        if (taskId == null) {
            throw new IllegalArgumentException("taskId is required or could not be resolved for commentId: " + commentId);
        }

        final Long resolvedTaskId = taskId;
        TaskCommentSummaryDto existing = taskCommentQueryPort.getTaskComments(resolvedTaskId, userId).stream()
                .filter(comment -> commentId.equals(comment.id()))
                .findFirst()
                .orElse(null);
        String content = patch.containsKey("content")
                ? stringPatchValue(patch, "content")
                : existing != null ? existing.content() : null;
        List<Long> mentionedUserIds = patch.containsKey("mentionedUserIds")
                ? longListPatchValue(patch, "mentionedUserIds")
                : existing != null ? existing.mentionedUserIds() : null;
        if (!hasText(content)) {
            throw new IllegalArgumentException("patchTaskComment requires content, or the existing comment must be readable.");
        }

        return pendingAiActionService.create(
                userId,
                sessionId,
                "patchTaskComment",
                "Patch comment " + commentId + " on task " + resolvedTaskId,
                args("taskId", resolvedTaskId, "commentId", commentId, "patch", patch, "reason", reason),
                args("taskId", resolvedTaskId, "commentId", commentId, "patch", patch, "reason", reason),
                () -> taskCommentQueryPort.updateTaskComment(resolvedTaskId, commentId, content, mentionedUserIds,
                        userId));
    }

    @Tool("Delete a comment from a task. Requires confirmation.")
    public Object deleteTaskComment(
            @Nullable @P("Optional. The ID of the task. If not provided, the system will resolve it automatically.") Long taskId,
            @P("The ID of the comment") Long commentId) {
        log.info("[AiTool] deleteTaskComment called for task {} comment {}", taskId, commentId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        if (taskId == null && jdbcTemplate != null) {
            try {
                taskId = jdbcTemplate.queryForObject(
                        "select task_id from comments where id = ?",
                        Long.class,
                        commentId
                );
                log.info("[AiTool] Resolved taskId {} for commentId {} from database", taskId, commentId);
            } catch (Exception e) {
                log.warn("[AiTool] Failed to resolve taskId for commentId {} via JDBC: {}", commentId, e.getMessage());
            }
        }
        if (taskId == null) {
            throw new IllegalArgumentException("taskId is required or could not be resolved for commentId: " + commentId);
        }

        final Long resolvedTaskId = taskId;
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteTaskComment",
                "Delete comment " + commentId + " on task " + resolvedTaskId,
                args("taskId", resolvedTaskId, "commentId", commentId),
                null,
                () -> taskCommentQueryPort.deleteTaskComment(resolvedTaskId, commentId, userId));
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

    @Tool("Update a project member's role (MANAGER, MEMBER). Requires confirmation.")
    public Object updateMemberRole(
            @P("The ID of the project") Long projectId,
            @P("The ID of the target user to update role") Long targetUserId,
            @P("The new role (MANAGER, MEMBER)") String role) {
        log.info("[AiTool] updateMemberRole called for project {} target {} role {}", projectId, targetUserId, role);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateMemberRole",
                "Update member role of user " + targetUserId + " to " + role + " in project " + projectId,
                args("projectId", projectId, "targetUserId", targetUserId, "role", role),
                null,
                () -> {
                    projectInsightsPort.updateMemberRole(projectId, targetUserId, role, userId);
                    return "Member role updated successfully";
                });
    }

    @Tool("Remove a member from a project by user ID. Requires confirmation.")
    public Object removeMember(
            @P("The ID of the project") Long projectId,
            @P("The ID of the target user to remove") Long targetUserId) {
        log.info("[AiTool] removeMember called for project {} target {}", projectId, targetUserId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        return pendingAiActionService.create(
                userId,
                sessionId,
                "removeMember",
                "Remove member " + targetUserId + " from project " + projectId,
                args("projectId", projectId, "targetUserId", targetUserId),
                null,
                () -> {
                    projectInsightsPort.removeMember(projectId, targetUserId, userId);
                    return "Member removed successfully";
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

    private boolean isCurrentUserConfirming(String actionId) {
        if (!hasText(actionId)) {
            return false;
        }
        String input = normalize(ToolExecutionContext.userInput());
        return input.contains(normalize(actionId))
                && isConfirmationInput(input);
    }

    private boolean isCurrentUserConfirming() {
        return isConfirmationInput(normalize(ToolExecutionContext.userInput()));
    }

    private boolean isConfirmationInput(String input) {
        if (input.matches("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$") || input.startsWith("confirm_action")) {
            return true;
        }
        return input.contains("confirm") || input.contains("confirmed")
                || input.contains("xac nhan") || input.contains("dong y")
                || input.contains("thuc hien") || input.contains("apply")
                || input.contains("ok") || input.contains("yes") || input.contains("approve");
    }



    private void validatePatchField(String fieldName, Set<String> allowedFields) {
        if (!allowedFields.contains(fieldName)) {
            throw new IllegalArgumentException("Unsupported patch field: " + fieldName);
        }
    }

    private int clampSkillLevel(Integer level) {
        int rawLevel = level != null ? level : 1;
        return Math.max(1, Math.min(5, rawLevel));
    }

    private String stringPatchValue(Map<String, Object> patch, String fieldName) {
        Object value = patch.get(fieldName);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) || "<null>".equalsIgnoreCase(text) || "undefined".equalsIgnoreCase(text) ? null : text;
    }

    private String cleanString(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) || "<null>".equalsIgnoreCase(text) || "undefined".equalsIgnoreCase(text) ? null : text;
    }

    private Long longPatchValue(Map<String, Object> patch, String fieldName) {
        return toLong(patch.get(fieldName));
    }

    private Integer integerPatchValue(Map<String, Object> patch, String fieldName) {
        Object value = patch.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value for task patch field: " + fieldName, e);
        }
    }

    private Float floatPatchValue(Map<String, Object> patch, String fieldName) {
        Object value = patch.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        try {
            return Float.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number value for task patch field: " + fieldName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> longListPatchValue(Map<String, Object> patch, String fieldName) {
        Object value = patch.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::toLong)
                    .filter(item -> item != null)
                    .toList();
        }
        return Arrays.stream(value.toString().split(","))
                .map(this::toLong)
                .filter(item -> item != null)
                .toList();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private Map<String, Object> args(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizePatch(Object patchData) {
        if (patchData == null) return java.util.Collections.emptyMap();
        if (patchData instanceof Map) {
            return (Map<String, Object>) patchData;
        }
        if (patchData instanceof String strData) {
            String trimmed = strData.trim();
            if (trimmed.isEmpty() || trimmed.equals("{}")) return java.util.Collections.emptyMap();
            try {
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
                    try {
                        Object unquoted = PATCH_OBJECT_MAPPER.readValue(trimmed, Object.class);
                        if (unquoted instanceof String s) trimmed = s.trim();
                        else if (unquoted instanceof Map) return (Map<String, Object>) unquoted;
                    } catch (Exception ignored) {}
                }
                Object parsed = PATCH_OBJECT_MAPPER.readValue(trimmed, Object.class);
                if (parsed instanceof Map) {
                    return (Map<String, Object>) parsed;
                } else if (parsed instanceof String innerStr) {
                    return PATCH_OBJECT_MAPPER.readValue(innerStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                }
            } catch (Exception e) {
                log.warn("Failed to parse patch string: {}", patchData, e);
                throw new IllegalArgumentException("Invalid patch data format. Must be valid JSON object.", e);
            }
        }
        throw new IllegalArgumentException("Invalid patch data type: " + (patchData != null ? patchData.getClass().getSimpleName() : "null"));
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
