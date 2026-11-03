package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.workspace.chat.ChatMessageSummaryDto;
import com.taskpilot.contracts.workspace.chat.ChatSearchCriteria;
import com.taskpilot.contracts.workspace.chat.ProjectChatPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAiToolsTest {

    private ProjectChatPort projectChatPort;
    private PendingAiActionService pendingAiActionService;
    private ChatAiTools chatAiTools;

    @BeforeEach
    void setUp() {
        projectChatPort = Mockito.mock(ProjectChatPort.class);
        pendingAiActionService = new PendingAiActionService();
        chatAiTools = new ChatAiTools(projectChatPort, pendingAiActionService);
        ToolExecutionContext.set(new ToolExecutionContext.Context(100L, 200L, "test"));
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void testGetChannelMessages_Success() {
        ChatMessageSummaryDto msg = new ChatMessageSummaryDto(
                1L, 100L, "Alice", "Hello team!", "TEXT", null, null, Instant.now());
        when(projectChatPort.getRecentMessages(eq(10L), any(), eq(20), eq(100L)))
                .thenReturn(List.of(msg));

        Object result = chatAiTools.getChannelMessages(10L, 20);
        assertThat(result).isInstanceOf(List.class);
        List<?> list = (List<?>) result;
        assertThat(list).hasSize(1);
        verify(projectChatPort).getRecentMessages(10L, null, 20, 100L);
    }

    @Test
    void testGetChannelMessages_Empty() {
        when(projectChatPort.getRecentMessages(eq(10L), any(), anyInt(), eq(100L)))
                .thenReturn(List.of());

        Object result = chatAiTools.getChannelMessages(10L, 10);
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("hiện chưa có tin nhắn nào");
    }

    @Test
    void testSearchChatMessages_Success() {
        ChatMessageSummaryDto msg = new ChatMessageSummaryDto(
                2L, 101L, "Bob", "Deadline is tomorrow", "TEXT", null, null, Instant.now());
        when(projectChatPort.searchMessages(eq(10L), any(ChatSearchCriteria.class), eq(100L)))
                .thenReturn(List.of(msg));

        Object result = chatAiTools.searchChatMessages(10L, "deadline", null, 15);
        assertThat(result).isInstanceOf(List.class);
        List<?> list = (List<?>) result;
        assertThat(list).hasSize(1);
    }

    @Test
    void testSendChannelAnnouncement_CreatesPendingAction() {
        when(projectChatPort.canAnnounce(10L, 100L)).thenReturn(true);
        Object result = chatAiTools.sendChannelAnnouncement(10L, "Important: Meeting at 3pm!");
        assertThat(result).isInstanceOf(ConfirmationRequiredDto.class);
        ConfirmationRequiredDto conf = (ConfirmationRequiredDto) result;
        assertThat(conf.confirmationRequired()).isTrue();
        assertThat(conf.toolName()).isEqualTo("sendChannelAnnouncement");
        assertThat(conf.actionId()).isNotBlank();
        assertThat(conf.summary()).contains("Gửi thông báo lên kênh dự án #10");

        // Verify executing confirmed action triggers postAnnouncement
        ChatMessageSummaryDto posted = new ChatMessageSummaryDto(
                99L, 100L, "Alice", "Important: Meeting at 3pm!", "ANNOUNCEMENT", null, null, Instant.now());
        when(projectChatPort.postAnnouncement(10L, "Important: Meeting at 3pm!", 100L)).thenReturn(posted);

        Object executed = pendingAiActionService.confirm(conf.actionId(), 100L, 200L);
        assertThat(executed).isEqualTo(posted);
        verify(projectChatPort).postAnnouncement(10L, "Important: Meeting at 3pm!", 100L);
    }

    @Test
    void testSendChannelAnnouncement_BlankContent() {
        Object result = chatAiTools.sendChannelAnnouncement(10L, "");
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("Nội dung thông báo không được để trống");
    }

    @Test
    void testSendChannelAnnouncement_ForbiddenNonManager() {
        when(projectChatPort.canAnnounce(10L, 100L)).thenReturn(false);
        Object result = chatAiTools.sendChannelAnnouncement(10L, "Hello");
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("Chỉ quản lý dự án");
    }
}
