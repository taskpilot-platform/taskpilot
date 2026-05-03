package com.taskpilot.ai.tools;

import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.contracts.assignment.dto.ProjectDueDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.aiquery.dto.*;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
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

    @Tool("""
            Use this tool when the user asks about the status, progress, or health of a specific project.
            Typical intents include: "tien do du an", "bao cao du an", "project status", "progress report".
            Provide the project ID, and this tool returns a short status summary for that project.
            """)
    public ProjectStatusDto getProjectStatus(@P("The ID of the project to query") Long projectId) {
        log.info("[AiTool] getProjectStatus called for project {}", projectId);
        return projectInsightsPort.getProjectStatus(projectId);
    }

    @Tool("""
            Use this tool when the user asks who is busy or available in a project team.
            Typical intents include: "ai ranh", "load team", "workload", "team availability".
            Provide the project ID to get a workload snapshot of members in that project.
            """)
    public List<MemberWorkloadDto> getMemberWorkload(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getMemberWorkload called for project {}", projectId);
        return memberAnalyticsPort.getMemberWorkloadForProject(projectId);
    }

    @Tool("""
            Use this tool when the user asks for the list of members in a specific project.
            Typical intents include: "thanh vien du an", "ai trong du an", "project members".
            Provide the project ID. This tool returns member IDs, names, roles, and skills for that project.
            """)
    public List<ProjectMemberDto> getProjectMembers(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getProjectMembers called for project {}", projectId);
        return projectInsightsPort.getProjectMembers(projectId);
    }

    @Tool("""
            Use this tool when the user asks for workload details of a specific member.
            Typical intents include: "member workload", "load cua thanh vien", "dang lam bao nhieu task".
            Provide the member ID. This tool returns open tasks, overdue tasks, and estimated hours.
            """)
    public MemberWorkloadDto getMemberWorkloadByMemberId(@P("The ID of the member") Long memberId) {
        log.info("[AiTool] getMemberWorkloadByMemberId called for member {}", memberId);
        return memberAnalyticsPort.getMemberWorkload(memberId);
    }

    @Tool("""
            Use this tool when the user asks for task details before assigning or analyzing it.
            Typical intents include: "task details", "chi tiet cong viec", "yeu cau task".
            Provide the task ID. This tool returns task name, description, difficulty, skills, and deadline.
            """)
    public TaskDetailDto getTaskDetails(@P("The ID of the task") Long taskId) {
        log.info("[AiTool] getTaskDetails called for task {}", taskId);
        return taskCommandPort.getTaskDetails(taskId);
    }

    @Tool("""
            Use this tool when the user explicitly asks to assign a task to a member.
            Provide taskId, memberId, and a short reason. This tool performs the assignment.
            """)
    public TaskAssignmentResultDto assignTaskToMember(
            @P("The ID of the task") Long taskId,
            @P("The ID of the member") Long memberId,
            @P("Reason for the assignment") String reason) {
        log.info("[AiTool] assignTaskToMember called for task {} -> member {}", taskId, memberId);
        // Sandbox logic simulation is disabled here (false) to perform real operation if connected to real DB
        return taskCommandPort.assignTaskToMember(taskId, memberId, reason, false);
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

    // =========================================================================
    // NEW CRUD TOOLS (TODO Implementations)
    // =========================================================================

    @Tool("""
            Use this tool to fetch all tasks belonging to a specific project.
            Provide the project ID.
            """)
    public Object getTasksByProject(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getTasksByProject called for project {}", projectId);
        return "Not implemented"; // Replace with actual return type later
    }

    @Tool("""
            Use this tool to fetch subtasks of a specific task.
            Provide the parent task ID.
            """)
    public Object getSubtasks(@P("The ID of the parent task") Long parentTaskId) {
        log.info("[AiTool] getSubtasks called for parent task {}", parentTaskId);
        return "Not implemented";
    }

    @Tool("""
            Use this tool to fetch comments made on a specific task.
            Provide the task ID.
            """)
    public Object getTaskComments(@P("The ID of the task") Long taskId) {
        log.info("[AiTool] getTaskComments called for task {}", taskId);
        return "Not implemented";
    }

    @Tool("""
            Use this tool to update the status of a task (e.g. TODO, IN_PROGRESS, REVIEW, DONE).
            Provide the task ID and the new status.
            """)
    public Object updateTaskStatus(
            @P("The ID of the task") Long taskId,
            @P("The new status (TODO, IN_PROGRESS, REVIEW, DONE)") String status) {
        log.info("[AiTool] updateTaskStatus called for task {} -> {}", taskId, status);
        return "Not implemented";
    }

    @Tool("""
            Use this tool to fetch all sprints belonging to a specific project.
            Provide the project ID.
            """)
    public Object getSprintsByProject(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getSprintsByProject called for project {}", projectId);
        return "Not implemented";
    }

    @Tool("""
            Use this tool to create a new task in a project.
            Provide project ID, title, priority, and optional sprint ID.
            """)
    public Object createTask(
            @P("The project ID") Long projectId,
            @P("Title of the task") String title,
            @P("Priority (LOW, MEDIUM, HIGH, URGENT)") String priority,
            @P("Optional sprint ID") Long sprintId) {
        log.info("[AiTool] createTask called for project {}", projectId);
        return "Not implemented";
    }
}
