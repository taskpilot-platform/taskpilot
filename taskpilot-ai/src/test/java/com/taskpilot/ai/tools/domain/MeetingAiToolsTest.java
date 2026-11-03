package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.MeetingSummarizationService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.workspace.meeting.MeetingDetailDto;
import com.taskpilot.contracts.workspace.meeting.MeetingListItemDto;
import com.taskpilot.contracts.workspace.meeting.MeetingSourceKind;
import com.taskpilot.contracts.workspace.meeting.MeetingSummaryDto;
import com.taskpilot.contracts.workspace.meeting.MeetingTaskCreationResultDto;
import com.taskpilot.contracts.workspace.meeting.ProjectMeetingPort;
import com.taskpilot.contracts.workspace.meeting.ScheduleMeetingCommand;
import com.taskpilot.contracts.workspace.meeting.TaskActionItemDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingAiToolsTest {

    private ProjectMeetingPort projectMeetingPort;
    private MeetingSummarizationService meetingSummarizationService;
    private TaskCommandPort taskCommandPort;
    private PendingAiActionService pendingAiActionService;
    private MeetingAiTools meetingAiTools;

    @BeforeEach
    void setUp() {
        projectMeetingPort = Mockito.mock(ProjectMeetingPort.class);
        meetingSummarizationService = Mockito.mock(MeetingSummarizationService.class);
        taskCommandPort = Mockito.mock(TaskCommandPort.class);
        pendingAiActionService = new PendingAiActionService();
        meetingAiTools = new MeetingAiTools(
                projectMeetingPort,
                meetingSummarizationService,
                taskCommandPort,
                pendingAiActionService);
        ToolExecutionContext.set(new ToolExecutionContext.Context(100L, 200L, "test"));
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void testListProjectMeetings_Success() {
        MeetingListItemDto meeting = new MeetingListItemDto(
                1L, "Sprint Planning", "ACTIVE", Instant.now(), null, Instant.now(), null,
                100L, "Alice", false, false, false, false);
        when(projectMeetingPort.listMeetings(eq(10L), eq("ACTIVE"), eq(20), eq(100L)))
                .thenReturn(List.of(meeting));

        Object result = meetingAiTools.listProjectMeetings(10L, "ACTIVE", 20);
        assertThat(result).isInstanceOf(List.class);
        List<?> list = (List<?>) result;
        assertThat(list).hasSize(1);
        verify(projectMeetingPort).listMeetings(10L, "ACTIVE", 20, 100L);
    }

    @Test
    void testListProjectMeetings_Empty() {
        when(projectMeetingPort.listMeetings(eq(10L), any(), anyInt(), eq(100L)))
                .thenReturn(Collections.emptyList());

        Object result = meetingAiTools.listProjectMeetings(10L, null, null);
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("hiện chưa có cuộc họp nào");
    }

    @Test
    void testGetMeetingDetails_Success() {
        MeetingListItemDto listItem = new MeetingListItemDto(
                1L, "Review Meeting", "ENDED", Instant.now(), null, Instant.now(), Instant.now(),
                100L, "Alice", true, true, false, true);
        MeetingDetailDto detail = new MeetingDetailDto(
                listItem, "Discuss sprint 3", Collections.emptyList(),
                55L, "recording.mp4", "/projects/10/files/55", "{}");

        when(projectMeetingPort.getMeetingDetails(10L, 1L, 100L)).thenReturn(detail);

        Object result = meetingAiTools.getMeetingDetails(10L, 1L);
        assertThat(result).isInstanceOf(MeetingDetailDto.class);
        MeetingDetailDto dto = (MeetingDetailDto) result;
        assertThat(dto.meeting().title()).isEqualTo("Review Meeting");
        assertThat(dto.recordingFileName()).isEqualTo("recording.mp4");
    }

    @Test
    void testScheduleMeeting_CreatesConfirmationRequired() {
        Object result = meetingAiTools.scheduleMeeting(
                10L,
                "Daily Standup",
                "Morning sync",
                "2026-11-04T09:00:00Z",
                "2026-11-04T09:30:00Z",
                true);

        assertThat(result).isInstanceOf(ConfirmationRequiredDto.class);
        ConfirmationRequiredDto conf = (ConfirmationRequiredDto) result;
        assertThat(conf.confirmationRequired()).isTrue();
        assertThat(conf.toolName()).isEqualTo("scheduleMeeting");
        assertThat(conf.arguments()).containsEntry("projectId", 10L);
        assertThat(conf.arguments()).containsEntry("title", "Daily Standup");

        // Execute pending action
        MeetingListItemDto listItem = new MeetingListItemDto(
                2L, "Daily Standup", "SCHEDULED", null, null, null, null,
                100L, "Alice", false, false, false, false);
        MeetingDetailDto created = new MeetingDetailDto(
                listItem, "Morning sync", Collections.emptyList(), null, null, null, null);

        when(projectMeetingPort.scheduleMeeting(eq(10L), any(ScheduleMeetingCommand.class), eq(100L)))
                .thenReturn(created);

        Object executed = pendingAiActionService.confirm(conf.actionId(), 100L, 200L);
        assertThat(executed).isEqualTo(created);
        verify(projectMeetingPort).scheduleMeeting(eq(10L), any(ScheduleMeetingCommand.class), eq(100L));
    }

    @Test
    void testSummarizeMeeting_Success() {
        TaskActionItemDto actionItem = new TaskActionItemDto(
                1, "Deploy to staging", "Verify staging environment", "Alice", 100L, false, "2026-11-05", "HIGH", "Alice will deploy");
        MeetingSummaryDto summary = new MeetingSummaryDto(
                1L,
                "Sprint Planning",
                "Overview of upcoming sprint",
                List.of("Approved release roadmap"),
                List.of(actionItem),
                List.of("Do we have enough compute?"),
                MeetingSourceKind.TRANSCRIPT,
                Instant.now(),
                false);

        when(meetingSummarizationService.summarizeMeeting(10L, 1L, "TRANSCRIPT", false, 100L))
                .thenReturn(summary);

        Object result = meetingAiTools.summarizeMeeting(10L, 1L, "TRANSCRIPT", false);
        assertThat(result).isInstanceOf(MeetingSummaryDto.class);
        MeetingSummaryDto resDto = (MeetingSummaryDto) result;
        assertThat(resDto.overview()).isEqualTo("Overview of upcoming sprint");
        assertThat(resDto.actionItems()).hasSize(1);
        assertThat(resDto.keyDecisions()).contains("Approved release roadmap");
    }

    @Test
    void testCreateTasksFromMeetingActionItems_Success() {
        TaskActionItemDto item1 = new TaskActionItemDto(
                1, "Fix bug #123", "Investigate null pointer in auth", "Alice", 100L, false, "2026-11-06", "HIGH", "Alice please fix");
        MeetingSummaryDto summary = new MeetingSummaryDto(
                1L, "Bug Triage", "Triage session", List.of(), List.of(item1), List.of(),
                MeetingSourceKind.NOTES, Instant.now(), false);

        when(meetingSummarizationService.summarizeMeeting(10L, 1L, null, false, 100L))
                .thenReturn(summary);

        Object result = meetingAiTools.createTasksFromMeetingActionItems(10L, 1L, null);
        assertThat(result).isInstanceOf(ConfirmationRequiredDto.class);
        ConfirmationRequiredDto conf = (ConfirmationRequiredDto) result;
        assertThat(conf.toolName()).isEqualTo("createTasksFromMeetingActionItems");

        // Mock task creation
        TaskSummaryDto createdTask = new TaskSummaryDto(
                501L, 10L, null, null, "Fix bug #123", "Investigate null pointer in auth", "TODO", "HIGH", 5, 100L, "Alice", "2026-11-06", null, null);
        when(taskCommandPort.createTask(
                eq(10L), eq("Fix bug #123"), anyString(), eq("HIGH"), any(), any(), any(), any(), any(), any(), eq(100L), any(), eq("2026-11-06"), eq(100L)))
                .thenReturn(createdTask);

        Object executionResult = pendingAiActionService.confirm(conf.actionId(), 100L, 200L);
        assertThat(executionResult).isInstanceOf(MeetingTaskCreationResultDto.class);
        MeetingTaskCreationResultDto taskResult = (MeetingTaskCreationResultDto) executionResult;
        assertThat(taskResult.created()).hasSize(1);
        assertThat(taskResult.created().get(0)).containsEntry("taskId", 501L);
        assertThat(taskResult.failed()).isEmpty();
    }
}
