package com.taskpilot.ai.tools;

import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.dto.RecommendAndAssignResult;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.PendingAiActionService;
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
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
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
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPilotAiTools {

    private final AutoAssignmentService autoAssignmentService;
    private final ProjectMemberPort projectMemberPort;
    private final ProjectInsightsPort projectInsightsPort;
    private final MemberAnalyticsPort memberAnalyticsPort;
    private final TaskCommandPort taskCommandPort;
    private final TaskCommentQueryPort taskCommentQueryPort;
    private final SprintQueryPort sprintQueryPort;
    private final SkillPort skillPort;
    private final PendingAiActionService pendingAiActionService;

    @Tool("""
            Use this tool when the user asks to list projects they participate in, joined projects,
            their current projects, or "du an cua toi". It returns projects where the current user is
            a project manager or member, including role and dates.
            """)
    public List<ProjectOverviewDto> getMyProjects() {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] getMyProjects called for user {}", userId);
        return projectInsightsPort.getMyProjects(userId);
    }

    @Tool("""
            Use this tool when the user asks about the status, progress, or health of a specific project.
            Typical intents include: "tien do du an", "bao cao du an", "project status", "progress report".
            Provide the project ID, and this tool returns a short status summary for that project.
            """)
    public ProjectStatusDto getProjectStatus(@P("The ID of the project to query") String projectId) {
        log.info("[AiTool] getProjectStatus called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return projectInsightsPort.getProjectStatus(toLong(projectId), userId);
    }

    @Tool("""
            Use this tool when the user asks who is busy or available in a project team.
            Typical intents include: "ai ranh", "load team", "workload", "team availability".
            Provide the project ID to get a workload snapshot of members in that project.
            """)
    public List<MemberWorkloadDto> getMemberWorkload(@P("The ID of the project") String projectId) {
        log.info("[AiTool] getMemberWorkload called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return memberAnalyticsPort.getMemberWorkloadForProject(toLong(projectId), userId);
    }

    @Tool("""
            Use this tool when the user asks for the list of members in a specific project.
            Typical intents include: "thanh vien du an", "ai trong du an", "project members".
            Provide the project ID. This tool returns member IDs, names, roles, and skills for that project.
            """)
    public List<ProjectMemberDto> getProjectMembers(@P("The ID of the project") String projectId) {
        log.info("[AiTool] getProjectMembers called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return projectInsightsPort.getProjectMembers(toLong(projectId), userId);
    }

    @Tool("""
            Use this tool to fetch labels configured for a project.
            Provide the project ID. It returns label IDs, names, and colors.
            """)
    public List<LabelSummaryDto> getProjectLabels(@P("The ID of the project") String projectId) {
        log.info("[AiTool] getProjectLabels called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return projectInsightsPort.getProjectLabels(toLong(projectId), userId);
    }

    @Tool("""
            Use this tool when the user asks for workload details of a specific member.
            Typical intents include: "member workload", "load cua thanh vien", "dang lam bao nhieu task".
            Provide the member ID. This tool returns open tasks, overdue tasks, and estimated hours.
            """)
    public MemberWorkloadDto getMemberWorkloadByMemberId(@P("The ID of the member") String memberId) {
        log.info("[AiTool] getMemberWorkloadByMemberId called for member {}", memberId);
        Long userId = ToolExecutionContext.requireUserId();
        return memberAnalyticsPort.getMemberWorkload(toLong(memberId), userId);
    }

    @Tool("""
            Use this tool when the user asks for task details before assigning or analyzing it.
            Typical intents include: "task details", "chi tiet cong viec", "yeu cau task".
            Provide the task ID. This tool returns task name, description, difficulty, skills, and deadline.
            """)
    public TaskDetailDto getTaskDetails(@P("The ID of the task") String taskId) {
        log.info("[AiTool] getTaskDetails called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getTaskDetails(toLong(taskId), userId);
    }

    @Tool("""
            Use this tool when you need the active system skill directory for a form, validation, or task assignment.
            Provide a keyword to search, or an empty keyword to return the first active skills. Use the returned skill
            names exactly when filling task required skills.
            """)
    public List<SkillDto> searchSystemSkills(
            @P("Skill search keyword. Use empty string to list common active skills.") String keyword) {
        String safeKeyword = keyword == null ? "" : keyword.trim();
        log.info("[AiTool] searchSystemSkills called keyword='{}'", safeKeyword);
        return skillPort.search(safeKeyword);
    }

    @Tool("""
            Use this tool when the user explicitly asks to assign a task to a member.
            Provide taskId, memberId, and a short reason. This tool performs the assignment.
            If the user names a specific assignee but does not provide memberId, use assignTaskToMemberByName instead.
            """)
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

    @Tool("""
            Use this tool when the user explicitly asks to assign a task to a named member, for example
            "assign task 68 to Julia Design" or "phân công task 68 cho Julia".
            This is a direct user override and must be preferred over recommendAndAssignTask.
            The tool resolves the task project, finds the member by name in that project, and creates a pending
            confirmation for the real assignment.
            """)
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

    @Tool("""
            Use this tool when the user asks to recommend the best member for a concrete task and also assign it
            in the same request, for example: "goi y roi gan luon", "phan cong task nay cho nguoi phu hop",
            "recommend and assign". This tool reads the task when project ID, skills, or difficulty are omitted,
            runs candidate scoring, picks the top candidate, and performs the real assignment.

            If neither the task nor the provided arguments contain required skills, this tool will NOT assign and
            will return a message asking for task skills. Only use it when the user clearly wants assignment applied.
            """)
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

    @Tool("""
            Use this tool when the user provides or changes the required skills for a task.
            Provide the task ID and comma-separated skill names exactly from the system skill directory.
            This updates real task data and therefore returns a pending confirmation action.
            If the user is filling a missing-skills assignment form, call this before assignment or use
            recommendAndAssignTask with the provided skills so the confirmation can save skills and assign together.
            """)
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

    @Tool("""
            Use this tool only after the user explicitly confirms a pending write action by sending the action ID.
            It performs the previously prepared real database write.
            """)
    public Object confirmPendingAction(@P("Pending action ID returned by a confirmationRequired tool result") String actionId) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        if (!isCurrentUserConfirming(actionId)) {
            return "Confirmation not accepted. Ask the user to confirm this exact action ID before executing: "
                    + actionId;
        }
        return pendingAiActionService.confirm(actionId, userId, sessionId);
    }

    @Tool("""
            Use this tool when the user explicitly cancels a pending write action by action ID.
            """)
    public Object cancelPendingAction(@P("Pending action ID to cancel") String actionId) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        pendingAiActionService.cancel(actionId, userId, sessionId);
        return Map.of("cancelled", true, "actionId", actionId);
    }

    @Tool("""
            Use this tool when the user wants to find suitable people for a task but does not specify task difficulty.
            Typical intents include: "ai lam", "chon nguoi", "goi y dev", "find candidates".
            Provide the project ID and a comma-separated list of skills. This tool uses a default difficulty of 5.
            """)
    public AutoAssignmentResponse findBestCandidates(
            @P("The project ID") String projectId,
            @P("Comma-separated list of required skill names, e.g. 'Java, Spring Boot, React'") String skills) {
        return recommendAssignmentCandidates(projectId, skills, "5");
    }

    @Tool("""
            Use this tool IMMEDIATELY when the user wants to assign a task or project to a member,
            or asks for recommendations on who should do the work.
            Typical intents include: "phan cong", "giao task", "ai lam", "chon nguoi", "goi y dev",
            "recommend candidate", "assign member".
            Provide the project ID, required skills, and a difficulty level (1-10). This tool runs AHP scoring
            with the current heuristic mode and returns ranked candidates.
            """)
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

    @Tool("""
            Use this tool when the user asks about projects due soon within a number-of-days window.
            Typical intents include: "trong X ngay toi", "sap toi han", "upcoming projects", "due soon".
            Provide daysAhead (default 7). This tool returns projects with due dates in that window.
            """)
    public List<ProjectDueDto> getUpcomingProjects(
            @P("Number of days ahead to check (default 7). Note: send as string like '7'") String daysAhead) {
        int parsedDays = 7;
        if (daysAhead != null && !daysAhead.isBlank()) {
            try { parsedDays = Integer.parseInt(daysAhead.trim()); } catch (Exception ignored) {}
        }
        int safeDays = Math.max(1, Math.min(90, parsedDays));
        Long userId = ToolExecutionContext.requireUserId();

        LocalDate fromDate = LocalDate.now();
        LocalDate toDate = fromDate.plusDays(safeDays);

        return projectMemberPort.findUpcomingProjects(userId, fromDate, toDate, 20);
    }

    @Tool("""
            Use this tool when the user specifies a concrete date range or a phrase that can be translated
            into a concrete date range (e.g. "next week", "from 2026-05-01 to 2026-05-07").
            Provide fromDate and toDate in YYYY-MM-DD format. This tool returns projects due within that range.
            """)
    public List<ProjectDueDto> findProjectsDue(
            @P("Start date in YYYY-MM-DD format") String fromDate,
            @P("End date in YYYY-MM-DD format") String toDate) {
        
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(fromDate);
            to = LocalDate.parse(toDate);
        } catch (DateTimeParseException | NullPointerException ex) {
            return List.of();
        }

        if (to.isBefore(from)) {
            return List.of();
        }

        Long userId = ToolExecutionContext.requireUserId();
        return projectMemberPort.findUpcomingProjects(userId, from, to, 20);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            log.warn("[AiTool] Failed to parse Long from: {}", s);
            return null;
        }
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

    @Tool("""
            Use this tool to fetch all tasks belonging to a specific project.
            Provide the project ID. Do not use this when the user asks for tasks that are unassigned,
            not assigned yet, "ch", "chua", "chưa", "ch dc", "ch đc", "chua duoc phan cong",
            or "chưa được phân công"; use getUnassignedTasksByProject instead.
            """)
    public Object getTasksByProject(@P("The ID of the project") String projectId) {
        log.info("[AiTool] getTasksByProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getTasksByProject(toLong(projectId), userId);
    }

    @Tool("""
            Use this tool when the user asks which tasks in a project are unassigned, not assigned yet,
            "ch", "chua", "chưa", "ch dc", "ch đc", "chua duoc phan cong", "chưa được phân công",
            "task nao chua gan", or asks for work that still needs an owner.
            Provide the project ID. It returns only tasks whose assignee is empty, with required skills and difficulty
            when available, so the assistant can ask only for missing fields.
            """)
    public Object getUnassignedTasksByProject(@P("The ID of the project") String projectId) {
        log.info("[AiTool] getUnassignedTasksByProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getUnassignedTasksByProject(toLong(projectId), userId);
    }

    @Tool("""
            Use this tool to fetch subtasks of a specific task.
            Provide the parent task ID.
            """)
    public Object getSubtasks(@P("The ID of the parent task") Long parentTaskId) {
        log.info("[AiTool] getSubtasks called for parent task {}", parentTaskId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getSubtasks(parentTaskId, userId);
    }

    @Tool("""
            Use this tool to fetch comments made on a specific task.
            Provide the task ID.
            """)
    public Object getTaskComments(@P("The ID of the task") Long taskId) {
        log.info("[AiTool] getTaskComments called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommentQueryPort.getTaskComments(taskId, userId);
    }

    @Tool("""
            Use this tool to create a comment on a task, or reply to a task comment.
            Provide task ID and content. parentCommentId is optional for replies; mentionedUserIds is optional.
            This tool performs a real database modification and requires final user confirmation.
            """)
    public Object createTaskComment(
            @P("The ID of the task") Long taskId,
            @P("Comment content") String content,
            @P("Optional parent comment ID when replying") Long parentCommentId,
            @P("Optional mentioned user IDs") List<Long> mentionedUserIds) {
        log.info("[AiTool] createTaskComment called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "createTaskComment",
                "Create comment on task " + taskId,
                args("taskId", taskId, "content", content, "parentCommentId", parentCommentId,
                        "mentionedUserIds", mentionedUserIds),
                null,
                () -> taskCommentQueryPort.createTaskComment(taskId, content, parentCommentId, mentionedUserIds,
                        userId));
    }

    @Tool("""
            Use this tool to update an existing task comment authored by the current user.
            Provide task ID, comment ID, new content, and optional mentioned user IDs.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to delete a task comment. Authors and project managers may delete comments according
            to project permissions.
            This tool performs a real database modification and requires final user confirmation.
            """)
    public Object deleteTaskComment(
            @P("The ID of the task") Long taskId,
            @P("The ID of the comment") Long commentId) {
        log.info("[AiTool] deleteTaskComment called for task {} comment {}", taskId, commentId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteTaskComment",
                "Delete comment " + commentId + " on task " + taskId,
                args("taskId", taskId, "commentId", commentId),
                null,
                () -> taskCommentQueryPort.deleteTaskComment(taskId, commentId, userId));
    }

    @Tool("""
            Use this tool to update the status of a task (e.g. TODO, IN_PROGRESS, REVIEW, DONE).
            Provide the task ID and the new status.
            This tool updates real task data and should only be used when the user explicitly asks for a status change.
            """)
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

    @Tool("""
            Use this tool to update task fields such as title, description, status, priority, position, labels,
            difficulty, required skills, assignee, start date, or due date.
            For unchanged fields pass null. Dates should be ISO-8601 instants or YYYY-MM-DD.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to delete a task. The backend enforces whether the current user can delete it
            (reporter or project manager).
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to move a task on the kanban board by setting status and position.
            Provide the task ID, target status, and target position.
            This tool performs a real database modification and requires final user confirmation.
            """)
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
            Use this tool to fetch all sprints belonging to a specific project.
            Provide the project ID.
            """)
    public Object getSprintsByProject(@P("The ID of the project") String projectId) {
        log.info("[AiTool] getSprintsByProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return sprintQueryPort.getSprintsByProject(toLong(projectId), userId);
    }

    @Tool("""
            Use this tool to create a label in a project. Only project managers can perform this.
            Provide project ID, label name, and optional hex color (#RRGGBB).
            This tool performs a real database modification and requires final user confirmation.
            """)
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
                null,
                () -> projectInsightsPort.createProjectLabel(projectId, name, color, userId));
    }

    @Tool("""
            Use this tool to delete a project label. Only project managers can perform this.
            Provide project ID and label ID.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to create a new project.
            Provide the project name. Optional fields include description, startDate (YYYY-MM-DD),
            and endDate (YYYY-MM-DD).
            This tool creates real database data and requires final user confirmation.
            """)
    public Object createProject(
            @P("Name of the project") String projectName,
            @P("Optional description of the project") String description,
            @P("Optional start date in YYYY-MM-DD format") String startDate,
            @P("Optional end date in YYYY-MM-DD format") String endDate) {
        log.info("[AiTool] createProject called with name={}", projectName);
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

    @Tool("""
            Use this tool to update details of an existing project.
            Provide the project ID. Optional fields include name, description, status (ACTIVE, COMPLETED, ARCHIVED),
            heuristicMode (BALANCED, SKILL_FIT_ONLY, WORKLOAD_ONLY), workflowMode (STANDARD, SCRUM, KANBAN),
            startDate (YYYY-MM-DD), and endDate (YYYY-MM-DD).
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to join an existing project using an invitation code.
            Provide the projectCode (invitation code).
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to leave an existing project (remove membership).
            Provide the project ID.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to update the role of a project member. Only project managers can perform this.
            Provide the project ID, target user ID, and new role (MANAGER, MEMBER).
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to remove a member from a project. Only project managers can perform this.
            Provide the project ID and target user ID to remove.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to archive a project to make it read-only. Only project managers can perform this.
            Provide the project ID.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to restore an archived project to active status. Only project managers can perform this.
            Provide the project ID.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to permanently delete a project and all its data. Only project managers can perform this.
            Provide the project ID.
            This tool performs a real database modification and requires final user confirmation.
            """)
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
            Use this tool to create a new task in a project.
            Provide project ID and title. Optional fields include description, priority, parent task ID,
            sprint ID, position, label IDs, required skill IDs, difficulty, assignee ID, startDate, and dueDate.
            Dates should be ISO-8601 instants or YYYY-MM-DD.
            This tool creates real task data and should only be used when the user explicitly asks to create a task.
            """)
    public Object createTask(
            @P("The project ID") Long projectId,
            @P("Title of the task") String title,
            @P("Priority (LOW, MEDIUM, HIGH, URGENT)") String priority,
            @P("Optional description") String description,
            @P("Optional kanban position") Float position,
            @P("Optional parent task ID") Long parentTaskId,
            @P("Optional sprint ID") Long sprintId,
            @P("Optional task difficulty 1-10. Note: send as string like '5'") String difficultyLevel,
            @P("Optional label ID list") List<Long> labelIds,
            @P("Optional required skill ID list") List<Long> requiredSkillIds,
            @P("Optional assignee user ID") Long assigneeId,
            @P("Optional start date as ISO-8601 instant or YYYY-MM-DD") String startDate,
            @P("Optional due date as ISO-8601 instant or YYYY-MM-DD") String dueDate) {
        log.info("[AiTool] createTask called for project {}", projectId);
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
                "createTask",
                "Create task \"" + title + "\" in project " + projectId,
                args("projectId", projectId, "title", title, "priority", priority, "description", description,
                        "position", position, "parentTaskId", parentTaskId, "sprintId", sprintId,
                        "difficultyLevel", finalDifficultyLevel, "labelIds", labelIds,
                        "requiredSkillIds", requiredSkillIds, "assigneeId", assigneeId,
                        "startDate", startDate, "dueDate", dueDate),
                null,
                () -> taskCommandPort.createTask(projectId, title, description, priority, position, parentTaskId,
                        sprintId, finalDifficultyLevel, labelIds, requiredSkillIds, assigneeId, startDate, dueDate,
                        userId));
    }

    @Tool("""
            Use this tool to fetch the backlog of a specific project, which contains unscheduled tasks
            and all sprints (including active, completed, and planned sprints) with their tasks.
            Typical intents include: "xem backlog", "sprint backlog", "backlog du an".
            Provide the project ID.
            """)
    public Object getSprintBacklog(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getSprintBacklog called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return sprintQueryPort.getSprintBacklog(projectId, userId);
    }

    @Tool("""
            Use this tool to fetch the active sprint board for a specific project, which contains
            tasks in the active sprint organized by columns/statuses.
            Typical intents include: "board sprint", "sprint board", "active board", "bang sprint dang chay".
            Provide the project ID.
            """)
    public Object getSprintBoard(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getSprintBoard called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return sprintQueryPort.getSprintBoard(projectId, userId);
    }

    @Tool("""
            Use this tool to plan and create a new sprint in a specific project.
            Provide the project ID and sprint name. Optional arguments include startDate (YYYY-MM-DD),
            endDate (YYYY-MM-DD), and goal.
            This tool creates real database data and requires final user confirmation.
            """)
    public Object createSprint(
            @P("The project ID") Long projectId,
            @P("Name of the sprint, e.g. 'Sprint 3'") String name,
            @P("Optional start date in YYYY-MM-DD format") String startDate,
            @P("Optional end date in YYYY-MM-DD format") String endDate,
            @P("Optional goal or objective of the sprint") String goal) {
        log.info("[AiTool] createSprint called for project {}", projectId);
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

    @Tool("""
            Use this tool to update a planning or active sprint's details.
            Provide project ID and sprint ID. Optional fields include name, startDate, endDate, and goal.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to delete a planning sprint. Only project managers can perform this.
            Provide project ID and sprint ID.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to start a planned sprint in a specific project.
            Provide the project ID and sprint ID.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to mark an active sprint as completed in a specific project.
            Provide the project ID and sprint ID.
            This tool performs a real database modification and requires final user confirmation.
            """)
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

    @Tool("""
            Use this tool to move or assign a task to a specific sprint (or remove it from sprint by setting sprintId to null).
            Provide the task ID and the sprint ID (or null to put it in the backlog).
            This tool performs a real database modification and requires final user confirmation.
            """)
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
                && (input.contains("confirm") || input.contains("confirmed")
                        || input.contains("xac nhan") || input.contains("dong y")
                        || input.contains("thuc hien") || input.contains("apply"));
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
            result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return result;
    }
}
