package com.taskpilot.ai.tools.selection;

import com.taskpilot.ai.service.ToolMeta;
import com.taskpilot.ai.service.ToolScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSelectionPolicyTest {

    private ToolSelectionPolicy policy;
    private Map<String, ToolMeta> toolRegistry;

    @BeforeEach
    void setUp() {
        policy = new ToolSelectionPolicy();
        toolRegistry = new HashMap<>();

        // Core tools
        register("queryProjects", Set.of(ToolScope.PROJECT), List.of("du an", "project"), 50, true, true);
        register("confirmPendingAction", Set.of(ToolScope.GENERAL), List.of("confirm", "xac nhan"), 60, true, false);
        register("cancelPendingAction", Set.of(ToolScope.GENERAL), List.of("cancel", "huy"), 40, true, false);

        // Knowledge / RAG
        register("searchProjectKnowledge", Set.of(ToolScope.KNOWLEDGE), List.of("tai lieu", "quy dinh", "quy trinh", "dac ta", "rag"), 60, false, true);

        // Workspace Files
        register("listProjectFiles", Set.of(ToolScope.FILE), List.of("file", "tep", "tep tin", "danh sach file", "pdf", "docx"), 55, false, true);
        register("searchProjectFiles", Set.of(ToolScope.FILE), List.of("tim file", "file ten", "ten file"), 55, false, true);
        register("getProjectFileLink", Set.of(ToolScope.FILE), List.of("link file", "tai file", "download", "tai xuong"), 50, false, true);

        // Workspace Chat
        register("getChannelMessages", Set.of(ToolScope.CHAT), List.of("tin nhan", "chat", "nhom chat", "kenh chat", "tin nhan gan day"), 55, false, true);
        register("searchChatMessages", Set.of(ToolScope.CHAT), List.of("tim tin nhan", "ai nhac", "nhac toi"), 55, false, true);
        register("sendChannelAnnouncement", Set.of(ToolScope.CHAT), List.of("gui thong bao", "dang thong bao", "thong bao len", "thong bao cho nhom"), 70, false, false);

        // Notifications
        register("getMyNotifications", Set.of(ToolScope.NOTIFICATION), List.of("thong bao", "cua toi", "chua doc"), 50, false, true);
        register("markAllNotificationsRead", Set.of(ToolScope.NOTIFICATION), List.of("danh dau da doc"), 50, false, false);

        // Workspace Meetings
        register("listProjectMeetings", Set.of(ToolScope.MEETING), List.of("cuoc hop", "lich hop", "meeting", "buoi hop"), 55, false, true);
        register("getMeetingDetails", Set.of(ToolScope.MEETING), List.of("chi tiet cuoc hop", "ban ghi cuoc hop"), 45, false, true);
        register("scheduleMeeting", Set.of(ToolScope.MEETING), List.of("dat lich", "len lich", "dat lich hop", "tao cuoc hop"), 70, false, false);
        register("summarizeMeeting", Set.of(ToolScope.MEETING), List.of("tom tat cuoc hop", "bien ban", "quyet dinh cuoc hop", "recap"), 65, false, true);
        register("createTasksFromMeetingActionItems", Set.of(ToolScope.MEETING, ToolScope.TASK), List.of("action item", "tao task tu", "tu bien ban"), 75, false, false);

        // Task & Assignment
        register("updateTaskStatus", Set.of(ToolScope.TASK), List.of("chuyen task", "trang thai", "doi trang thai", "status"), 60, false, false);
        register("updateTask", Set.of(ToolScope.TASK), List.of("update task"), 10, false, false); // Deprecated
        register("recommendTaskAssignmentCandidates", Set.of(ToolScope.ASSIGNMENT, ToolScope.AHP), List.of("goi y nguoi", "phu hop", "thanh vien phu hop"), 65, false, true);
        register("recommendAssignmentCandidates", Set.of(ToolScope.ASSIGNMENT), List.of("candidate"), 40, false, true);
    }

    private void register(String name, Set<ToolScope> scopes, List<String> keywords, int priority, boolean essential, boolean readOnly) {
        toolRegistry.put(name, new ToolMeta(name, scopes, keywords, priority, essential, readOnly, true, false));
    }

    @Test
    @DisplayName("Case 1: Document policy inquiry selects searchProjectKnowledge")
    void testCase1_KnowledgeInquiry() {
        List<String> tools = policy.selectTools("Quy định nghỉ phép của nhóm ghi gì?", toolRegistry, Set.of(ToolScope.KNOWLEDGE), false, false, 3L);
        assertTrue(tools.contains("searchProjectKnowledge"));
        assertTrue(tools.size() <= 12);
    }

    @Test
    @DisplayName("Case 2: List project files selects listProjectFiles and not announcement")
    void testCase2_ListFiles() {
        List<String> tools = policy.selectTools("Dự án 3 có file pdf nào", toolRegistry, Set.of(ToolScope.FILE), false, false, 3L);
        assertTrue(tools.contains("listProjectFiles") || tools.contains("searchProjectFiles"));
        assertFalse(tools.contains("sendChannelAnnouncement"));
        assertTrue(tools.size() <= 12);
    }

    @Test
    @DisplayName("Case 3: Get file download link")
    void testCase3_GetFileLink() {
        List<String> tools = policy.selectTools("Cho tôi link tải file 15", toolRegistry, Set.of(ToolScope.FILE), false, false, 3L);
        assertTrue(tools.contains("getProjectFileLink"));
    }

    @Test
    @DisplayName("Case 4: Recent channel chat messages")
    void testCase4_RecentMessages() {
        List<String> tools = policy.selectTools("Tin nhắn gần đây trong nhóm dự án 3", toolRegistry, Set.of(ToolScope.CHAT), false, false, 3L);
        assertTrue(tools.contains("getChannelMessages"));
        assertFalse(tools.contains("getMyNotifications"));
    }

    @Test
    @DisplayName("Case 5: Channel announcement broadcast (Manager)")
    void testCase5_ChannelAnnouncement() {
        List<String> tools = policy.selectTools("Gửi thông báo lên nhóm chat dự án 3: họp 9h", toolRegistry, Set.of(ToolScope.CHAT), true, false, 3L);
        assertTrue(tools.contains("sendChannelAnnouncement"));
        assertFalse(tools.contains("markAllNotificationsRead"));
    }

    @Test
    @DisplayName("Case 6: User personal notifications")
    void testCase6_MyNotifications() {
        List<String> tools = policy.selectTools("Thông báo của tôi", toolRegistry, Set.of(ToolScope.NOTIFICATION), false, false, 3L);
        assertTrue(tools.contains("getMyNotifications"));
        assertFalse(tools.contains("sendChannelAnnouncement"));
    }

    @Test
    @DisplayName("Case 7: Schedule meeting")
    void testCase7_ScheduleMeeting() {
        List<String> tools = policy.selectTools("Đặt lịch họp sprint review 9h sáng mai", toolRegistry, Set.of(ToolScope.MEETING), true, false, 3L);
        assertTrue(tools.contains("scheduleMeeting"));
    }

    @Test
    @DisplayName("Case 8: Meeting summary")
    void testCase8_MeetingSummary() {
        List<String> tools = policy.selectTools("Tóm tắt cuộc họp hôm qua", toolRegistry, Set.of(ToolScope.MEETING), false, false, 3L);
        assertTrue(tools.contains("summarizeMeeting"));
        assertTrue(tools.contains("listProjectMeetings"));
    }

    @Test
    @DisplayName("Case 9: Create tasks from meeting action items")
    void testCase9_CreateTasksFromActionItems() {
        List<String> tools = policy.selectTools("Tạo task từ biên bản cuộc họp 12", toolRegistry, Set.of(ToolScope.MEETING, ToolScope.TASK), true, false, 3L);
        assertTrue(tools.contains("createTasksFromMeetingActionItems"));
        assertFalse(tools.contains("recommendAssignmentCandidates"));
    }

    @Test
    @DisplayName("Case 10: Recommend candidate for task ('phù hợp' does not trigger meeting tools)")
    void testCase10_RecommendCandidate() {
        List<String> tools = policy.selectTools("Gợi ý người phù hợp cho task 45", toolRegistry, Set.of(ToolScope.ASSIGNMENT), false, false, 3L);
        assertTrue(tools.contains("recommendTaskAssignmentCandidates"));
        assertFalse(tools.contains("scheduleMeeting"));
    }

    @Test
    @DisplayName("Case 11: Task status update (uses updateTaskStatus, excludes deprecated updateTask)")
    void testCase11_UpdateTaskStatus() {
        List<String> tools = policy.selectTools("Chuyển task 45 sang DONE", toolRegistry, Set.of(ToolScope.TASK), true, false, 3L);
        assertTrue(tools.contains("updateTaskStatus"));
        assertFalse(tools.contains("updateTask"));
    }

    @Test
    @DisplayName("Case 12: Generic greeting contains <= 4 tools (only core essential)")
    void testCase12_Greeting() {
        List<String> tools = policy.selectTools("chào bạn", toolRegistry, Set.of(ToolScope.GENERAL), false, false, null);
        assertFalse(tools.contains("scheduleMeeting"));
        assertFalse(tools.contains("sendChannelAnnouncement"));
        assertFalse(tools.contains("createTasksFromMeetingActionItems"));
        assertTrue(tools.size() <= 4);
    }
}
