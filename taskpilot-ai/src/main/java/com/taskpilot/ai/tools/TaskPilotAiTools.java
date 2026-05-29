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
    public ProjectStatusDto getProjectStatus(@P("The ID of the project to query") Long projectId) {
        log.info("[AiTool] getProjectStatus called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return projectInsightsPort.getProjectStatus(projectId, userId);
    }

    @Tool("""
            Use this tool when the user asks who is busy or available in a project team.
            Typical intents include: "ai ranh", "load team", "workload", "team availability".
            Provide the project ID to get a workload snapshot of members in that project.
            """)
    public List<MemberWorkloadDto> getMemberWorkload(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getMemberWorkload called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return memberAnalyticsPort.getMemberWorkloadForProject(projectId, userId);
    }

    @Tool("""
            Use this tool when the user asks for the list of members in a specific project.
            Typical intents include: "thanh vien du an", "ai trong du an", "project members".
            Provide the project ID. This tool returns member IDs, names, roles, and skills for that project.
            """)
    public List<ProjectMemberDto> getProjectMembers(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getProjectMembers called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return projectInsightsPort.getProjectMembers(projectId, userId);
    }

    @Tool("""
            Use this tool when the user asks for workload details of a specific member.
            Typical intents include: "member workload", "load cua thanh vien", "dang lam bao nhieu task".
            Provide the member ID. This tool returns open tasks, overdue tasks, and estimated hours.
            """)
    public MemberWorkloadDto getMemberWorkloadByMemberId(@P("The ID of the member") Long memberId) {
        log.info("[AiTool] getMemberWorkloadByMemberId called for member {}", memberId);
        Long userId = ToolExecutionContext.requireUserId();
        return memberAnalyticsPort.getMemberWorkload(memberId, userId);
    }

    @Tool("""
            Use this tool when the user asks for task details before assigning or analyzing it.
            Typical intents include: "task details", "chi tiet cong viec", "yeu cau task".
            Provide the task ID. This tool returns task name, description, difficulty, skills, and deadline.
            """)
    public TaskDetailDto getTaskDetails(@P("The ID of the task") Long taskId) {
        log.info("[AiTool] getTaskDetails called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getTaskDetails(taskId, userId);
    }

    @Tool("""
            Use this tool when the user explicitly asks to assign a task to a member.
            Provide taskId, memberId, and a short reason. This tool performs the assignment.
            """)
    public Object assignTaskToMember(
            @P("The ID of the task") Long taskId,
            @P("The ID of the member") Long memberId,
            @P("Reason for the assignment") String reason) {
        log.info("[AiTool] assignTaskToMember called for task {} -> member {}", taskId, memberId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        String safeReason = hasText(reason) ? reason : "Task assigned by AI tool";

        return pendingAiActionService.create(
                userId,
                sessionId,
                "assignTaskToMember",
                "Assign task " + taskId + " to member " + memberId,
                args("taskId", taskId, "memberId", memberId, "reason", safeReason),
                null,
                () -> taskCommandPort.assignTaskToMember(taskId, memberId, safeReason, userId, false));
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
            @P("The ID of the task to assign") Long taskId,
            @P("Optional project ID. If omitted, it is read from task details") Long projectId,
            @P("Optional comma-separated required skill names. If omitted, task required skills are used") String skills,
            @P("Optional task difficulty 1-10. If omitted, task difficulty is used") Integer difficulty,
            @P("Reason to store with the assignment") String reason) {
        log.info("[AiTool] recommendAndAssignTask called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        TaskDetailDto task = taskCommandPort.getTaskDetails(taskId, userId);
        Long resolvedProjectId = projectId != null ? projectId : task.projectId();
        String resolvedSkills = hasText(skills) ? skills : task.requiredSkills();

        if (!hasText(resolvedSkills)) {
            return new RecommendAndAssignResult(false, taskId, resolvedProjectId, null, null, reason,
                    null, null,
                    "Task " + taskId + " is missing required skills. Please provide skills before assigning.");
        }

        int resolvedDifficulty = difficulty != null ? difficulty
                : (task.difficultyLevel() != null ? task.difficultyLevel() : 5);
        resolvedDifficulty = Math.max(1, Math.min(10, resolvedDifficulty));

        AutoAssignmentResponse recommendation = autoAssignmentService.recommendCandidates(
                resolvedProjectId,
                parseSkills(resolvedSkills),
                resolvedDifficulty,
                userId);

        if (recommendation.candidates() == null || recommendation.candidates().isEmpty()) {
            return new RecommendAndAssignResult(false, taskId, resolvedProjectId, null, null, reason,
                    recommendation, null,
                    "No eligible candidate found for task " + taskId + ".");
        }

        CandidateScore selected = recommendation.candidates().get(0);
        String safeReason = hasText(reason)
                ? reason
                : "AI selected the top-ranked candidate based on skill fit, workload, and project heuristic mode.";
        RecommendAndAssignResult preview = new RecommendAndAssignResult(false, taskId, resolvedProjectId,
                selected.getUserId(), selected.getFullName(), safeReason, recommendation, null,
                "Ready to assign task " + taskId + " to " + selected.getFullName() + " after confirmation.");

        return pendingAiActionService.create(
                userId,
                sessionId,
                "recommendAndAssignTask",
                "Assign task " + taskId + " to " + selected.getFullName() + " (top recommended candidate)",
                args("taskId", taskId, "projectId", resolvedProjectId, "skills", resolvedSkills,
                        "difficulty", resolvedDifficulty, "memberId", selected.getUserId(), "reason", safeReason),
                preview,
                () -> {
                    TaskAssignmentResultDto assignment = taskCommandPort.assignTaskToMember(
                            taskId,
                            selected.getUserId(),
                            safeReason,
                            userId,
                            false);
                    return new RecommendAndAssignResult(true, taskId, resolvedProjectId, selected.getUserId(),
                            selected.getFullName(), safeReason, recommendation, assignment,
                            "Task " + taskId + " assigned to " + selected.getFullName() + ".");
                });
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
            @P("The project ID") Long projectId,
            @P("Comma-separated list of required skill names, e.g. 'Java, Spring Boot, React'") String skills) {
        return recommendAssignmentCandidates(projectId, skills, 5);
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
            @P("The project ID") Long projectId,
            @P("Comma-separated list of required skill names") String skills,
            @P("Task difficulty 1-10") Integer difficulty) {
        log.info("[AiTool] recommendAssignmentCandidates called for project {}", projectId);

        Long userId = ToolExecutionContext.requireUserId();
        int safeDifficulty = difficulty == null ? 5 : Math.max(1, Math.min(10, difficulty));
        List<String> requiredSkills = parseSkills(skills);

        return autoAssignmentService.recommendCandidates(projectId, requiredSkills, safeDifficulty, userId);
    }

    @Tool("""
            Use this tool when the user asks about projects due soon within a number-of-days window.
            Typical intents include: "trong X ngay toi", "sap toi han", "upcoming projects", "due soon".
            Provide daysAhead (default 7). This tool returns projects with due dates in that window.
            """)
    public List<ProjectDueDto> getUpcomingProjects(
            @P("Number of days ahead to check (default 7)") Integer daysAhead) {
        int safeDays = daysAhead == null ? 7 : Math.max(1, Math.min(90, daysAhead));
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

    private List<String> parseSkills(String skills) {
        if (skills == null || skills.isBlank()) {
            return List.of();
        }
        return Arrays.stream(skills.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // =========================================================================
    // Project/task CRUD tools backed by the real project data ports.
    // =========================================================================

    @Tool("""
            Use this tool to fetch all tasks belonging to a specific project.
            Provide the project ID.
            """)
    public Object getTasksByProject(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getTasksByProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getTasksByProject(projectId, userId);
    }

    @Tool("""
            Use this tool when the user asks which tasks in a project are unassigned, not assigned yet,
            "chua duoc phan cong", "task nao chua gan", or asks for work that still needs an owner.
            Provide the project ID. It returns only tasks whose assignee is empty, with required skills and difficulty
            when available, so the assistant can ask only for missing fields.
            """)
    public Object getUnassignedTasksByProject(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getUnassignedTasksByProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return taskCommandPort.getUnassignedTasksByProject(projectId, userId);
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
            Use this tool to fetch all sprints belonging to a specific project.
            Provide the project ID.
            """)
    public Object getSprintsByProject(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getSprintsByProject called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        return sprintQueryPort.getSprintsByProject(projectId, userId);
    }

    @Tool("""
            Use this tool to create a new task in a project.
            Provide project ID and title. Optional fields include description, priority, parent task ID,
            sprint ID, difficulty, assignee ID, and dueDate as ISO-8601 or YYYY-MM-DD.
            This tool creates real task data and should only be used when the user explicitly asks to create a task.
            """)
    public Object createTask(
            @P("The project ID") Long projectId,
            @P("Title of the task") String title,
            @P("Priority (LOW, MEDIUM, HIGH, URGENT)") String priority,
            @P("Optional description") String description,
            @P("Optional parent task ID") Long parentTaskId,
            @P("Optional sprint ID") Long sprintId,
            @P("Optional task difficulty 1-10") Integer difficultyLevel,
            @P("Optional assignee user ID") Long assigneeId,
            @P("Optional due date as ISO-8601 instant or YYYY-MM-DD") String dueDate) {
        log.info("[AiTool] createTask called for project {}", projectId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "createTask",
                "Create task \"" + title + "\" in project " + projectId,
                args("projectId", projectId, "title", title, "priority", priority, "description", description,
                        "parentTaskId", parentTaskId, "sprintId", sprintId, "difficultyLevel", difficultyLevel,
                        "assigneeId", assigneeId, "dueDate", dueDate),
                null,
                () -> taskCommandPort.createTask(projectId, title, description, priority, parentTaskId, sprintId,
                        difficultyLevel, assigneeId, dueDate, userId));
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
