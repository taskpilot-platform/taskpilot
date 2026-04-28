package com.taskpilot.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.contracts.assignment.dto.ProjectDueDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPilotAiTools {

    private final AutoAssignmentService autoAssignmentService;
    private final ProjectMemberPort projectMemberPort;
    private final ObjectMapper objectMapper;

    @Tool("""
            Use this tool when the user asks about the status, progress, or health of a specific project.
            Typical intents include: "tien do du an", "bao cao du an", "project status", "progress report".
            Provide the project ID, and this tool returns a short status summary for that project.
            """)
    public String getProjectStatus(@P("The ID of the project to query") Long projectId) {
        log.info("[AiTool] getProjectStatus called for project {}", projectId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("projectName", projectId != null && projectId == 2L ? "warehouse-update" : "unknown");
        payload.put("status", projectId != null && projectId == 2L ? "AT_RISK" : "IN_PROGRESS");
        payload.put("completionPercent", projectId != null && projectId == 2L ? 62 : 45);
        payload.put("openTasks", projectId != null && projectId == 2L ? 18 : 12);
        payload.put("overdueTasks", projectId != null && projectId == 2L ? 3 : 1);
        payload.put("mock", true);
        return toJson(payload);
    }

    @Tool("""
            Use this tool when the user asks who is busy or available in a project team.
            Typical intents include: "ai ranh", "load team", "workload", "team availability".
            Provide the project ID to get a workload snapshot of members in that project.
            """)
    public String getMemberWorkload(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getMemberWorkload called for project {}", projectId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("projectName", projectId != null && projectId == 2L ? "warehouse-update" : "unknown");
        payload.put("members", mockMemberWorkloads(projectId));
        payload.put("mock", true);
        return toJson(payload);
    }

    @Tool("""
            Use this tool when the user asks for the list of members in a specific project.
            Typical intents include: "thanh vien du an", "ai trong du an", "project members".
            Provide the project ID. This tool returns member IDs, names, roles, and skills for that project.
            """)
    public String getProjectMembers(@P("The ID of the project") Long projectId) {
        log.info("[AiTool] getProjectMembers called for project {}", projectId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("projectName", projectId != null && projectId == 2L ? "warehouse-update" : "unknown");
        payload.put("members", mockProjectMembers(projectId));
        payload.put("mock", true);
        return toJson(payload);
    }

    @Tool("""
            Use this tool when the user asks for workload details of a specific member.
            Typical intents include: "member workload", "load cua thanh vien", "dang lam bao nhieu task".
            Provide the member ID. This tool returns open tasks, overdue tasks, and estimated hours.
            """)
    public String getMemberWorkloadByMemberId(@P("The ID of the member") Long memberId) {
        log.info("[AiTool] getMemberWorkloadByMemberId called for member {}", memberId);

        if (memberId == null) {
            return "Missing required parameter: memberId";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId);
        payload.put("openTasks", memberId == 102L ? 9 : 4);
        payload.put("overdueTasks", memberId == 102L ? 2 : 0);
        payload.put("estimatedHours", memberId == 102L ? 36 : 18);
        payload.put("mock", true);
        return toJson(payload);
    }

    @Tool("""
            Use this tool when the user asks for task details before assigning or analyzing it.
            Typical intents include: "task details", "chi tiet cong viec", "yeu cau task".
            Provide the task ID. This tool returns task name, description, difficulty, skills, and deadline.
            """)
    public String getTaskDetails(@P("The ID of the task") Long taskId) {
        log.info("[AiTool] getTaskDetails called for task {}", taskId);

        if (taskId == null) {
            return "Missing required parameter: taskId";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("title", taskId == 7001L ? "Warehouse inventory sync" : "Generic task");
        payload.put("description", taskId == 7001L
                ? "Sync inventory counts between WMS and core system."
                : "Task description not available.");
        payload.put("difficulty", taskId == 7001L ? 7 : 4);
        payload.put("requiredSkills", taskId == 7001L ? List.of("Java", "PostgreSQL", "Kafka")
                : List.of("General"));
        payload.put("dueDate", LocalDate.now().plusDays(5).toString());
        payload.put("mock", true);
        return toJson(payload);
    }

    @Tool("""
            Use this tool when the user explicitly asks to assign a task to a member.
            Provide taskId, memberId, and a short reason. This tool performs the assignment.
            """)
    public String assignTaskToMember(
            @P("The ID of the task") Long taskId,
            @P("The ID of the member") Long memberId,
            @P("Reason for the assignment") String reason) {
        log.info("[AiTool] assignTaskToMember called for task {} -> member {}", taskId, memberId);

        if (taskId == null || memberId == null) {
            return "Missing required parameters: taskId, memberId";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("memberId", memberId);
        payload.put("status", "ASSIGNED");
        payload.put("reason", reason == null || reason.isBlank() ? "No reason provided" : reason);
        payload.put("mock", true);
        return toJson(payload);
    }

    @Tool("""
            Use this tool when the user wants to find suitable people for a task but does not specify task difficulty.
            Typical intents include: "ai lam", "chon nguoi", "goi y dev", "find candidates".
            Provide the project ID and a comma-separated list of skills. This tool uses a default difficulty of 5.
            """)
    public String findBestCandidates(
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
    public String recommendAssignmentCandidates(
            @P("The project ID") Long projectId,
            @P("Comma-separated list of required skill names") String skills,
            @P("Task difficulty 1-10") Integer difficulty) {
        log.info("[AiTool] recommendAssignmentCandidates called for project {}", projectId);

        if (projectId == null) {
            return "Missing required parameter: projectId";
        }

        Long userId = ToolExecutionContext.requireUserId();
        int safeDifficulty = difficulty == null ? 5 : Math.max(1, Math.min(10, difficulty));
        List<String> requiredSkills = parseSkills(skills);

        AutoAssignmentResponse response = autoAssignmentService.recommendCandidates(
                projectId, requiredSkills, safeDifficulty, userId);

        return toJson(response);
    }

    @Tool("""
            Use this tool when the user asks about projects due soon within a number-of-days window.
            Typical intents include: "trong X ngay toi", "sap toi han", "upcoming projects", "due soon".
            Provide daysAhead (default 7). This tool returns projects with due dates in that window.
            """)
    public String getUpcomingProjects(
            @P("Number of days ahead to check (default 7)") Integer daysAhead) {
        int safeDays = daysAhead == null ? 7 : Math.max(1, Math.min(90, daysAhead));
        Long userId = ToolExecutionContext.requireUserId();

        LocalDate fromDate = LocalDate.now();
        LocalDate toDate = fromDate.plusDays(safeDays);

        List<ProjectDueDto> projects = projectMemberPort.findUpcomingProjects(userId, fromDate, toDate, 20);
        return toJson(projects);
    }

    @Tool("""
            Use this tool when the user specifies a concrete date range or a phrase that can be translated
            into a concrete date range (e.g. "next week", "from 2026-05-01 to 2026-05-07").
            Provide fromDate and toDate in YYYY-MM-DD format. This tool returns projects due within that range.
            """)
    public String findProjectsDue(
            @P("Start date in YYYY-MM-DD format") String fromDate,
            @P("End date in YYYY-MM-DD format") String toDate) {
        if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
            return "Missing required parameters: fromDate, toDate";
        }

        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(fromDate);
            to = LocalDate.parse(toDate);
        } catch (DateTimeParseException ex) {
            return "Invalid date format. Use YYYY-MM-DD for fromDate and toDate.";
        }

        if (to.isBefore(from)) {
            return "Invalid date range: toDate must be on or after fromDate.";
        }

        Long userId = ToolExecutionContext.requireUserId();
        List<ProjectDueDto> projects = projectMemberPort.findUpcomingProjects(userId, from, to, 20);
        return toJson(projects);
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

    private List<Map<String, Object>> mockProjectMembers(Long projectId) {
        List<Map<String, Object>> members = new ArrayList<>();

        if (projectId != null && projectId == 2L) {
            members.add(mockMember(101L, "Nguyen Minh Anh", "Backend", List.of("Java", "PostgreSQL")));
            members.add(mockMember(102L, "Tran Hoang Phuc", "Backend", List.of("Java", "Kafka")));
            members.add(mockMember(103L, "Le Thu Quynh", "QA", List.of("Testing", "Automation")));
            members.add(mockMember(104L, "Pham Gia Bao", "Frontend", List.of("React", "TypeScript")));
            return members;
        }

        members.add(mockMember(201L, "Member A", "Backend", List.of("Java")));
        members.add(mockMember(202L, "Member B", "QA", List.of("Testing")));
        return members;
    }

    private List<Map<String, Object>> mockMemberWorkloads(Long projectId) {
        List<Map<String, Object>> workloads = new ArrayList<>();
        if (projectId != null && projectId == 2L) {
            workloads.add(mockWorkload(101L, "Nguyen Minh Anh", 5, 1, 22));
            workloads.add(mockWorkload(102L, "Tran Hoang Phuc", 9, 2, 36));
            workloads.add(mockWorkload(103L, "Le Thu Quynh", 3, 0, 12));
            workloads.add(mockWorkload(104L, "Pham Gia Bao", 4, 1, 18));
            return workloads;
        }
        workloads.add(mockWorkload(201L, "Member A", 4, 0, 16));
        workloads.add(mockWorkload(202L, "Member B", 2, 0, 8));
        return workloads;
    }

    private Map<String, Object> mockMember(Long id, String name, String role, List<String> skills) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("memberId", id);
        member.put("fullName", name);
        member.put("role", role);
        member.put("skills", skills);
        return member;
    }

    private Map<String, Object> mockWorkload(Long id, String name, int openTasks, int overdueTasks, int hours) {
        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("memberId", id);
        workload.put("fullName", name);
        workload.put("openTasks", openTasks);
        workload.put("overdueTasks", overdueTasks);
        workload.put("estimatedHours", hours);
        return workload;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("[AiTool] Failed to serialize tool output: {}", e.getMessage());
            return "{}";
        }
    }
}
