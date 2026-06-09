package com.taskpilot.ai.service;

import com.taskpilot.ai.tools.TaskPilotAiTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallingRegistryService {

    private final TaskPilotAiTools taskPilotAiTools;
    private final SmartRoutingService smartRoutingService;

    private List<ToolSpecification> toolSpecifications;
    private Map<String, ToolExecutor> toolExecutors;
    private Map<String, ToolMeta> toolMetadataRegistry;

    @PostConstruct
    void init() {
        ToolService toolService = new ToolService();
        toolService.tools(List.of(taskPilotAiTools));

        this.toolSpecifications = List.copyOf(toolService.toolSpecifications());
        this.toolExecutors = Map.copyOf(toolService.toolExecutors());

        initToolMetadata();

        log.info("[AI Tools] Registered {} tool specifications", this.toolSpecifications.size());
    }

    private void initToolMetadata() {
        toolMetadataRegistry = new HashMap<>();
        
        // GENERAL / PROJECT
        register("getMyProjects", Set.of(ToolScope.PROJECT, ToolScope.GENERAL, ToolScope.TASK), List.of("project", "du an", "my", "da", "task", "tao task", "tạo task"), 50, true);
        register("getProjectStatus", Set.of(ToolScope.PROJECT), List.of("status", "tinh trang", "tien do"), 40, false);
        register("getProjectLabels", Set.of(ToolScope.PROJECT), List.of("label", "nhan"), 10, false);
        register("createProject", Set.of(ToolScope.PROJECT), List.of("create project", "tao du an"), 30, false);
        register("updateProject", Set.of(ToolScope.PROJECT), List.of("update project", "cap nhat du an"), 20, false);
        register("patchProject", Set.of(ToolScope.PROJECT), List.of("update project", "cap nhat du an", "sua du an", "deadline project", "doi han du an"), 45, false);
        register("archiveProject", Set.of(ToolScope.PROJECT), List.of("archive", "luu tru"), 10, false);
        register("restoreProject", Set.of(ToolScope.PROJECT), List.of("restore", "khoi phuc"), 10, false);
        register("deleteProject", Set.of(ToolScope.PROJECT), List.of("delete project", "xoa du an"), 45, false);
        register("joinProject", Set.of(ToolScope.PROJECT), List.of("join", "tham gia"), 10, false);
        register("leaveProject", Set.of(ToolScope.PROJECT), List.of("leave", "roi khoi"), 10, false);
        register("createProjectLabel", Set.of(ToolScope.PROJECT), List.of("create label", "tao nhan"), 45, false);
        register("deleteProjectLabel", Set.of(ToolScope.PROJECT), List.of("delete label", "xoa nhan"), 10, false);
        register("getUpcomingProjects", Set.of(ToolScope.PROJECT), List.of("upcoming", "sap den", "deadline"), 20, false);
        register("findProjectsDue", Set.of(ToolScope.PROJECT), List.of("due", "den han"), 20, false);

        // TASK
        register("getTaskDetails", Set.of(ToolScope.TASK), List.of("chi tiet", "detail", "cv", "nv"), 50, true);
        register("getTasksByProject", Set.of(ToolScope.TASK, ToolScope.PROJECT), List.of("list task", "danh sach task", "cac cong viec", "cv", "nv"), 50, true);
        register("getUnassignedTasksByProject", Set.of(ToolScope.TASK, ToolScope.PROJECT, ToolScope.ASSIGNMENT), List.of("unassigned", "not assigned", "chua gan", "chua phan cong", "chua duoc phan cong", "trong", "ch"), 40, false);
        register("getSubtasks", Set.of(ToolScope.TASK), List.of("subtask", "task con"), 20, false);
        register("createTask", Set.of(ToolScope.TASK), List.of("create task", "new task", "tao task", "tạo task", "tao cong viec", "tạo công việc", "them task", "them cong viec"), 60, false);
        register("updateTask", Set.of(ToolScope.TASK), List.of("update task", "cap nhat cong viec", "sua task"), 30, false);
        register("patchTask", Set.of(ToolScope.TASK, ToolScope.ASSIGNMENT), List.of("update task", "cap nhat cong viec", "sua task", "deadline", "due date", "han chot", "hạn chót", "doi han", "đổi hạn", "reassign", "phan cong lai", "phân công lại", "assign", "giao task", "gan cho", "giao cho", "gán task", "phân công"), 65, false);
        register("updateTaskStatus", Set.of(ToolScope.TASK), List.of("status", "trang thai", "hoan thanh", "done", "todo"), 40, false);
        register("deleteTask", Set.of(ToolScope.TASK), List.of("delete task", "xoa task"), 45, false);
        register("moveTaskKanban", Set.of(ToolScope.TASK, ToolScope.PROJECT), List.of("move", "kanban", "board", "chuyen"), 20, false);

        // SPRINT
        register("getSprintsByProject", Set.of(ToolScope.SPRINT, ToolScope.PROJECT, ToolScope.TASK), List.of("sprint", "chu ky", "danh sach sprint"), 40, false);
        register("getSprintBacklog", Set.of(ToolScope.SPRINT, ToolScope.PROJECT, ToolScope.TASK), List.of("backlog"), 30, false);
        register("getSprintBoard", Set.of(ToolScope.SPRINT, ToolScope.PROJECT), List.of("board", "bang"), 30, false);
        register("createSprint", Set.of(ToolScope.SPRINT), List.of("create sprint", "tao sprint"), 30, false);
        register("updateSprint", Set.of(ToolScope.SPRINT), List.of("update sprint", "sua sprint"), 20, false);
        register("patchSprint", Set.of(ToolScope.SPRINT), List.of("update sprint", "sua sprint", "doi han sprint", "deadline sprint", "goal sprint"), 45, false);
        register("deleteSprint", Set.of(ToolScope.SPRINT), List.of("delete sprint", "xoa sprint"), 45, false);
        register("startSprint", Set.of(ToolScope.SPRINT), List.of("start", "bat dau"), 30, false);
        register("completeSprint", Set.of(ToolScope.SPRINT), List.of("complete", "hoan thanh"), 30, false);
        register("assignTaskToSprint", Set.of(ToolScope.SPRINT, ToolScope.TASK), List.of("assign sprint", "dua vao sprint"), 30, false);

        // COMMENT
        register("getMyTaskComments", Set.of(ToolScope.COMMENT, ToolScope.TASK, ToolScope.GENERAL), List.of("my comments", "comment cua toi", "comment cua minh", "binh luan cua toi", "binh luận của tôi", "toi da comment", "cmt"), 60, false);
        register("getTaskComments", Set.of(ToolScope.COMMENT, ToolScope.TASK), List.of("comment", "comments", "binh luan", "binh luận", "comment cua toi", "comment cua minh", "cmt"), 50, false);
        register("createTaskComment", Set.of(ToolScope.COMMENT, ToolScope.TASK), List.of("create comment", "add comment", "reply", "them comment", "them binh luan", "tra loi comment", "cmt"), 50, false);
        register("updateTaskComment", Set.of(ToolScope.COMMENT, ToolScope.TASK), List.of("update comment", "edit comment", "sua comment", "sua binh luan"), 30, false);
        register("patchTaskComment", Set.of(ToolScope.COMMENT, ToolScope.TASK), List.of("update comment", "edit comment", "sua comment", "sua binh luan", "mention"), 45, false);
        register("deleteTaskComment", Set.of(ToolScope.COMMENT, ToolScope.TASK), List.of("delete comment", "remove comment", "xoa comment", "xoa binh luan"), 30, false);

        // NOTIFICATION
        register("getMyNotifications", Set.of(ToolScope.NOTIFICATION, ToolScope.GENERAL), List.of("notification", "notifications", "unread", "thong bao", "thông báo", "chua doc", "chưa đọc", "tb", "ch"), 60, false);
        register("getUnreadNotificationCount", Set.of(ToolScope.NOTIFICATION, ToolScope.GENERAL), List.of("unread count", "so thong bao", "số thông báo", "bao nhieu thong bao", "chua doc", "chưa đọc", "tb", "ch"), 50, false);
        register("markNotificationRead", Set.of(ToolScope.NOTIFICATION), List.of("mark read", "da doc", "đã đọc", "doc thong bao", "đọc thông báo"), 50, false);
        register("markAllNotificationsRead", Set.of(ToolScope.NOTIFICATION), List.of("mark all read", "doc tat ca thong bao", "đọc tất cả thông báo", "tat ca da doc"), 50, false);

        // MEMBER / ASSIGNMENT / AHP
        register("getProjectMembers", Set.of(ToolScope.MEMBER, ToolScope.PROJECT), List.of("member", "thanh vien"), 40, false);
        register("getMemberWorkload", Set.of(ToolScope.MEMBER, ToolScope.PROJECT), List.of("workload", "khoi luong", "ban"), 30, false);
        register("getMemberWorkloadByMemberId", Set.of(ToolScope.MEMBER), List.of("workload", "khoi luong"), 20, false);
        register("updateMemberRole", Set.of(ToolScope.MEMBER, ToolScope.PROJECT), List.of("role", "vai tro", "quyen"), 20, false);
        register("removeMember", Set.of(ToolScope.MEMBER, ToolScope.PROJECT), List.of("remove member", "xoa thanh vien", "kick"), 20, false);
        register("searchSystemSkills", Set.of(ToolScope.MEMBER, ToolScope.AHP, ToolScope.ASSIGNMENT), List.of("skill", "ky nang"), 30, false);
        register("createSystemSkill", Set.of(ToolScope.MEMBER, ToolScope.GENERAL), List.of("create system skill", "tao system skill", "tao ky nang he thong", "thêm skill hệ thống"), 55, false);
        register("patchSystemSkill", Set.of(ToolScope.MEMBER), List.of("update system skill", "sua system skill", "cap nhat ky nang he thong", "sửa skill hệ thống"), 45, false);
        register("deleteSystemSkill", Set.of(ToolScope.MEMBER), List.of("delete system skill", "xoa system skill", "xoa ky nang he thong", "xóa skill hệ thống"), 30, false);
        register("getMySkills", Set.of(ToolScope.MEMBER, ToolScope.GENERAL), List.of("my skill", "skill cua toi", "ky nang cua toi", "kỹ năng của tôi"), 35, false);
        register("addMySkill", Set.of(ToolScope.MEMBER), List.of("add skill", "them skill", "them ky nang", "thêm kỹ năng"), 35, false);
        register("patchMySkill", Set.of(ToolScope.MEMBER), List.of("update skill", "cap nhat skill", "sua skill", "doi level skill", "đổi level skill"), 45, false);
        register("deleteMySkill", Set.of(ToolScope.MEMBER), List.of("delete skill", "remove skill", "xoa skill", "xóa kỹ năng"), 30, false);
        register("updateTaskRequiredSkills", Set.of(ToolScope.TASK, ToolScope.AHP, ToolScope.ASSIGNMENT), List.of("required skill", "ky nang can thiet"), 20, false);
        register("assignTaskToMember", Set.of(ToolScope.ASSIGNMENT, ToolScope.TASK), List.of("assign", "giao", "phan cong"), 65, false);
        register("assignTaskToMemberByName", Set.of(ToolScope.ASSIGNMENT, ToolScope.TASK), List.of("assign by name", "giao cho", "phân công cho", "phan cong cho"), 65, false);
        register("findBestCandidates", Set.of(ToolScope.AHP, ToolScope.ASSIGNMENT, ToolScope.MEMBER), List.of("best candidate", "ung vien", "phu hop nhat"), 30, false);
        register("recommendAssignmentCandidates", Set.of(ToolScope.AHP, ToolScope.ASSIGNMENT, ToolScope.MEMBER), List.of("recommend", "goi y"), 30, false);
        register("recommendTaskAssignmentCandidates", Set.of(ToolScope.TASK, ToolScope.AHP, ToolScope.ASSIGNMENT, ToolScope.MEMBER), List.of("recommend task", "goi y task", "rcm", "nguoi khac", "người khác", "so sanh", "so sánh", "compare", "reassign"), 70, false);
        register("recommendAndAssignTask", Set.of(ToolScope.TASK, ToolScope.ASSIGNMENT, ToolScope.AHP, ToolScope.MEMBER), List.of("assign", "giao viec", "phan cong", "phu hop", "best member", "nguoi phu hop"), 50, false);
        
        // GENERAL / ACTIONS
        register("confirmPendingAction", Set.of(ToolScope.GENERAL), List.of("confirm", "dong y", "xac nhan"), 40, false);
        register("cancelPendingAction", Set.of(ToolScope.GENERAL), List.of("cancel", "huy", "tu choi"), 40, false);
    }

    private void register(String name, Set<ToolScope> scopes, List<String> keywords, int priorityScore, boolean essential) {
        List<String> allKeywords = new java.util.ArrayList<>(keywords);
        allKeywords.add(name.toLowerCase());
        toolMetadataRegistry.put(name, new ToolMeta(name, scopes, allKeywords, priorityScore, essential));
    }

    public List<String> selectToolNames(String message, int maxTools, boolean expanded) {
        Set<ToolScope> activeScopes = smartRoutingService.detectScopes(message);
        String normalizedMsg = smartRoutingService.normalize(message);
        
        return toolMetadataRegistry.values().stream()
            .map(meta -> new ScoredTool(meta, calculateScore(meta, activeScopes, normalizedMsg, expanded)))
            .filter(st -> st.score() > 0)
            .sorted(Comparator.comparingInt(ScoredTool::score).reversed())
            .limit(maxTools)
            .map(st -> st.meta().toolName())
            .toList();
    }
    
    private int calculateScore(ToolMeta meta, Set<ToolScope> activeScopes, String normalizedMsg, boolean expanded) {
        int score = 0;
        boolean matched = false;
        
        if (meta.essential()) {
            score += 100;
            matched = true;
        }
        
        if (meta.scopes().stream().anyMatch(activeScopes::contains)) {
            score += 50;
            matched = true;
        }
        
        for (String kw : meta.keywords()) {
            if (normalizedMsg.contains(smartRoutingService.normalize(kw))) {
                score += 10;
                matched = true;
            }
        }
        
        if (expanded) {
            if (meta.scopes().contains(ToolScope.GENERAL)) { score += 30; matched = true; }
            if (meta.scopes().contains(ToolScope.PROJECT)) { score += 20; matched = true; }
            if (meta.scopes().contains(ToolScope.MEMBER)) { score += 20; matched = true; }
        }
        
        if (!matched) return 0;
        
        score += meta.priorityScore();
        return score;
    }

    public List<ToolSpecification> toolSpecifications() {
        return toolSpecifications;
    }

    public List<ToolSpecification> toolSpecificationsByName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return List.of();
        }
        return toolSpecifications.stream()
                .filter(spec -> toolName.equals(spec.name()))
                .collect(Collectors.toList());
    }

    public List<ToolSpecification> toolSpecificationsByNames(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        return toolSpecifications.stream()
                .filter(spec -> toolNames.contains(spec.name()))
                .collect(Collectors.toList());
    }

    public String execute(ToolExecutionRequest request) {
        ToolExecutor executor = toolExecutors.get(request.name());
        if (executor == null) {
            return "Tool not available: " + request.name();
        }

        try {
            String args = request.arguments();
            if (args != null) {
                String trimmed = args.trim();
                boolean isJsonObj = trimmed.startsWith("{") && trimmed.endsWith("}");
                if (!isJsonObj) {
                    log.warn("[AI Tools] Tool {} arguments is not a valid JSON object: '{}'. Overriding with '{}'", request.name(), args, "{}");
                    args = "{}";
                } else if (trimmed.contains("null")) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.Map<String, Object> map = mapper.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                        normalizeToolArguments(request.name(), map);
                        map.values().removeIf(java.util.Objects::isNull);
                        args = mapper.writeValueAsString(map);
                    } catch (Exception e) {
                        log.warn("[AI Tools] Failed to filter nulls from arguments for {}: {}", request.name(), e.getMessage());
                    }
                } else {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.Map<String, Object> map = mapper.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                        normalizeToolArguments(request.name(), map);
                        args = mapper.writeValueAsString(map);
                    } catch (Exception e) {
                        log.warn("[AI Tools] Failed to normalize arguments for {}: {}", request.name(), e.getMessage());
                    }
                }

                request = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id(request.id())
                        .name(request.name())
                        .arguments(args)
                        .build();
            }
            return executor.execute(request, null);
        } catch (Exception ex) {
            log.error("[AI Tools] Tool execution failed for {}: {}", request.name(), ex.getMessage(), ex);
            return "Tool execution failed: " + ex.getMessage();
        }
    }

    private void normalizeToolArguments(String toolName, Map<String, Object> args) {
        if (args == null) {
            return;
        }
        if ("patchTask".equals(toolName) && !args.containsKey("patchData") && args.get("patch") != null) {
            args.put("patchData", args.get("patch"));
        }
    }
}
