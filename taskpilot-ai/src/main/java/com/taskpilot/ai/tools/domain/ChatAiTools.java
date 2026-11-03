package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.workspace.chat.ChatMessageSummaryDto;
import com.taskpilot.contracts.workspace.chat.ChatSearchCriteria;
import com.taskpilot.contracts.workspace.chat.ProjectChatPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAiTools {

    private final ProjectChatPort projectChatPort;
    private final PendingAiActionService pendingAiActionService;

    @Tool("Đọc các tin nhắn trao đổi gần đây trong kênh thảo luận nhóm của dự án")
    public Object getChannelMessages(
            @P("ID dự án cần đọc tin nhắn") Long projectId,
            @P("Optional. Số lượng tin nhắn tối đa cần lấy (mặc định 20, tối đa 50)") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] getChannelMessages called for user={}, projectId={}, limit={}",
                userId, projectId, limit);

        if (projectId == null) {
            return "Project ID is required to read channel messages.";
        }

        int maxLimit = limit != null && limit > 0 ? Math.min(limit, 50) : 20;
        List<ChatMessageSummaryDto> messages = projectChatPort.getRecentMessages(projectId, null, maxLimit, userId);

        if (messages.isEmpty()) {
            return "Kênh thảo luận của dự án #" + projectId + " hiện chưa có tin nhắn nào.";
        }

        return messages.stream()
                .map(m -> m.content() != null && m.content().length() > 500
                        ? new ChatMessageSummaryDto(m.id(), m.senderId(), m.senderName(), m.content().substring(0, 497) + "...", m.messageType(), m.fileId(), m.fileName(), m.createdAt())
                        : m)
                .toList();
    }

    @Tool("Tìm kiếm tin nhắn trong kênh thảo luận nhóm của dự án theo từ khóa nội dung hoặc tên người gửi")
    public Object searchChatMessages(
            @P("ID dự án cần tìm kiếm tin nhắn") Long projectId,
            @P("Optional. Từ khóa nội dung tin nhắn cần tìm") String keyword,
            @P("Optional. Tên người gửi cần tìm") String senderName,
            @P("Optional. Số lượng tin nhắn tối đa (mặc định 20, tối đa 50)") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] searchChatMessages called for user={}, projectId={}, keyword='{}', sender='{}', limit={}",
                userId, projectId, keyword, senderName, limit);

        if (projectId == null) {
            return "Project ID is required to search chat messages.";
        }

        int maxLimit = limit != null && limit > 0 ? Math.min(limit, 50) : 20;
        ChatSearchCriteria criteria = new ChatSearchCriteria(keyword, senderName, null, null, maxLimit);
        List<ChatMessageSummaryDto> messages = projectChatPort.searchMessages(projectId, criteria, userId);

        if (messages.isEmpty()) {
            return "Không tìm thấy tin nhắn nào khớp với yêu cầu tìm kiếm trong dự án #" + projectId + ".";
        }

        return messages.stream()
                .map(m -> m.content() != null && m.content().length() > 500
                        ? new ChatMessageSummaryDto(m.id(), m.senderId(), m.senderName(), m.content().substring(0, 497) + "...", m.messageType(), m.fileId(), m.fileName(), m.createdAt())
                        : m)
                .toList();
    }

    @Tool("Gửi thông báo quan trọng lên kênh thảo luận nhóm dự án (cần người dùng xác nhận trước khi gửi)")
    public Object sendChannelAnnouncement(
            @P("ID dự án cần gửi thông báo") Long projectId,
            @P("Nội dung thông báo cần gửi lên kênh nhóm") String content) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] sendChannelAnnouncement called for user={}, session={}, projectId={}, content='{}'",
                userId, sessionId, projectId, content);

        if (projectId == null) {
            return "Project ID is required to send announcement.";
        }
        if (content == null || content.isBlank()) {
            return "Nội dung thông báo không được để trống.";
        }
        if (!projectChatPort.canAnnounce(projectId, userId)) {
            return "Chỉ quản lý dự án (Project Manager) mới có quyền đăng thông báo lên kênh chung.";
        }

        return pendingAiActionService.create(
                userId,
                sessionId,
                "sendChannelAnnouncement",
                "Gửi thông báo lên kênh dự án #" + projectId + ": " + content,
                Map.of("projectId", projectId, "content", content),
                Map.of("projectId", projectId, "content", content, "type", "ANNOUNCEMENT"),
                () -> projectChatPort.postAnnouncement(projectId, content, userId)
        );
    }
}
