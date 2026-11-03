package com.taskpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.contracts.workspace.chat.ChatMessageSummaryDto;
import com.taskpilot.contracts.workspace.meeting.MeetingDetailDto;
import com.taskpilot.contracts.workspace.meeting.MeetingListItemDto;
import com.taskpilot.contracts.workspace.meeting.MeetingSourceKind;
import com.taskpilot.contracts.workspace.meeting.MeetingSourceTextDto;
import com.taskpilot.contracts.workspace.meeting.MeetingSummaryDto;
import com.taskpilot.contracts.workspace.meeting.ProjectMeetingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingSummarizationServiceTest {

    private ProjectMeetingPort projectMeetingPort;
    private ObjectMapper objectMapper;
    private ObjectProvider chatModelProvider;
    private MeetingSummarizationService service;

    @BeforeEach
    void setUp() {
        projectMeetingPort = Mockito.mock(ProjectMeetingPort.class);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        chatModelProvider = Mockito.mock(ObjectProvider.class);
        when(chatModelProvider.getIfAvailable()).thenReturn(null); // test heuristic fallback

        service = new MeetingSummarizationService(projectMeetingPort, objectMapper, chatModelProvider);
    }

    @Test
    void testSummarizeMeeting_ReturnsCachedSummary() {
        String cachedJson = """
                {
                  "meetingId": 1,
                  "title": "Sprint Retrospective",
                  "overview": "Đã tóm tắt từ trước",
                  "keyDecisions": ["Chốt quy trình PR"],
                  "actionItems": [
                    {
                      "index": 1,
                      "title": "Cập nhật tài liệu",
                      "description": "Viết lại hướng dẫn git",
                      "assigneeName": "Bob",
                      "dueDate": "2026-11-10",
                      "priority": "HIGH"
                    }
                  ],
                  "openQuestions": [],
                  "sourceUsed": "TRANSCRIPT"
                }
                """;

        MeetingListItemDto listItem = new MeetingListItemDto(
                1L, "Sprint Retrospective", "ENDED", null, null, null, null,
                100L, "Alice", false, false, false, true);
        MeetingDetailDto detail = new MeetingDetailDto(
                listItem, "Retro sprint 2", Collections.emptyList(), null, null, null, cachedJson);

        when(projectMeetingPort.getMeetingDetails(10L, 1L, 100L)).thenReturn(detail);

        MeetingSummaryDto result = service.summarizeMeeting(10L, 1L, null, false, 100L);
        assertThat(result).isNotNull();
        assertThat(result.overview()).isEqualTo("Đã tóm tắt từ trước");
        assertThat(result.keyDecisions()).contains("Chốt quy trình PR");
        assertThat(result.actionItems()).hasSize(1);
        assertThat(result.actionItems().get(0).title()).isEqualTo("Cập nhật tài liệu");
        assertThat(result.fromCache()).isTrue();

        verify(projectMeetingPort, never()).getMeetingSourceText(any(), any(), any());
    }

    @Test
    void testSummarizeMeeting_EmptySourceText_ReturnsPlaceholder() {
        when(projectMeetingPort.getMeetingDetails(10L, 2L, 100L)).thenReturn(null);
        MeetingSourceTextDto emptySource = new MeetingSourceTextDto(
                2L, "Empty Meeting", null, null, null, Collections.emptyList(), MeetingSourceKind.NONE, Instant.now());
        when(projectMeetingPort.getMeetingSourceText(10L, 2L, 100L)).thenReturn(emptySource);

        MeetingSummaryDto result = service.summarizeMeeting(10L, 2L, null, false, 100L);
        assertThat(result).isNotNull();
        assertThat(result.overview()).contains("chưa có bản ghi, ghi chú");
        assertThat(result.sourceUsed()).isEqualTo(MeetingSourceKind.NONE);
        assertThat(result.fromCache()).isFalse();
    }

    @Test
    void testSummarizeMeeting_HeuristicExtractionFromNotes() {
        String notes = """
                Cuộc họp bàn về release Phase 4 TaskPilot.
                Quyết định: Thống nhất chốt API contracts trước thứ 4.
                Action item: Viết migration Flyway V40 cho bảng meeting.
                Nhiệm vụ: Triển khai ThoughtBlockStreamFilter để lọc thought tags.
                Câu hỏi: Dung lượng recording tối đa trên Supabase storage là bao nhiêu?
                """;

        when(projectMeetingPort.getMeetingDetails(10L, 3L, 100L)).thenReturn(null);
        MeetingSourceTextDto sourceText = new MeetingSourceTextDto(
                3L, "Phase 4 Sync", null, notes, "Mô tả họp", Collections.emptyList(), MeetingSourceKind.NOTES, Instant.now());
        when(projectMeetingPort.getMeetingSourceText(10L, 3L, 100L)).thenReturn(sourceText);

        MeetingSummaryDto result = service.summarizeMeeting(10L, 3L, "NOTES", false, 100L);
        assertThat(result).isNotNull();
        assertThat(result.keyDecisions()).isNotEmpty();
        assertThat(result.keyDecisions().get(0)).contains("Thống nhất chốt API contracts");
        assertThat(result.actionItems()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.openQuestions()).isNotEmpty();
        assertThat(result.openQuestions().get(0)).contains("Dung lượng recording tối đa");
        assertThat(result.sourceUsed()).isEqualTo(MeetingSourceKind.NOTES);

        verify(projectMeetingPort).saveAiSummary(eq(10L), eq(3L), anyString(), eq("NOTES"), eq(100L));
    }
}
