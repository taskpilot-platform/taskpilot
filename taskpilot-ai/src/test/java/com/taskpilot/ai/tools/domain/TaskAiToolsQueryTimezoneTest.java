package com.taskpilot.ai.tools.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.aiquery.dto.ProjectOverviewDto;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;

@ExtendWith(MockitoExtension.class)
class TaskAiToolsQueryTimezoneTest {

    @Mock
    private TaskCommandPort taskCommandPort;

    @Mock
    private ProjectInsightsPort projectInsightsPort;

    @Mock
    private PendingAiActionService pendingAiActionService;

    @BeforeEach
    void setUp() {
        ToolExecutionContext.set(new ToolExecutionContext.Context(10L, 100L, "test", List.of("queryTasks")));
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void dueTodayAndOverdueFollowProjectTimezoneAndClockAcrossDstAndHostZones() {
        TimeZone previous = TimeZone.getDefault();
        try {
            // Host running in UTC while project is Asia/Ho_Chi_Minh and another is America/New_York
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            // Instant = 2026-11-14T18:30:00Z -> already 2026-11-15 01:30 in Asia/Ho_Chi_Minh
            Clock hcmClock = Clock.fixed(Instant.parse("2026-11-14T18:30:00Z"), ZoneId.of("UTC"));
            TaskAiTools hcmTools = new TaskAiTools(taskCommandPort, projectInsightsPort, pendingAiActionService, hcmClock);

            when(projectInsightsPort.getMyProjects(10L)).thenReturn(List.of(
                    new ProjectOverviewDto(1L, "HCM", "desc", "ACTIVE", "MANAGER", "2026-11-01", "2026-12-01",
                            "2026-11-01T00:00:00Z", "Asia/Ho_Chi_Minh"),
                    new ProjectOverviewDto(2L, "NY", "desc", "ACTIVE", "MANAGER", "2026-03-01", "2026-04-01",
                            "2026-03-01T00:00:00Z", "America/New_York")
            ));
            when(taskCommandPort.getTasksByProject(1L, 10L)).thenReturn(List.of(
                    task(1L, 1L, "Due Nov 15 HCM", "IN_PROGRESS", "2026-11-15T16:59:59.999999Z", "Asia/Ho_Chi_Minh"),
                    task(2L, 1L, "Due Yesterday Overdue", "TODO", "2026-11-14T16:59:59.999999Z", "Asia/Ho_Chi_Minh"),
                    task(3L, 1L, "Done Yesterday Not Overdue", "DONE", "2026-11-14T16:59:59.999999Z", "Asia/Ho_Chi_Minh")
            ));

            @SuppressWarnings("unchecked")
            List<TaskAiTools.AiQueryTaskDto> hcmDueToday = (List<TaskAiTools.AiQueryTaskDto>) hcmTools.queryTasks(
                    "1", null, null, null, true, null, null, null, null, null);
            assertEquals(List.of(1L), hcmDueToday.stream().map(TaskAiTools.AiQueryTaskDto::id).toList());

            @SuppressWarnings("unchecked")
            List<TaskAiTools.AiQueryTaskDto> hcmOverdue = (List<TaskAiTools.AiQueryTaskDto>) hcmTools.queryTasks(
                    "1", null, null, true, null, null, null, null, null, null);
            assertEquals(List.of(2L), hcmOverdue.stream().map(TaskAiTools.AiQueryTaskDto::id).toList());

            // DST spring-forward in America/New_York: 2026-03-08 23:30 EDT = 2026-03-09T03:30:00Z
            Clock nyClock = Clock.fixed(Instant.parse("2026-03-09T03:30:00Z"), ZoneId.of("UTC"));
            TaskAiTools nyTools = new TaskAiTools(taskCommandPort, projectInsightsPort, pendingAiActionService, nyClock);
            when(taskCommandPort.getTasksByProject(2L, 10L)).thenReturn(List.of(
                    task(11L, 2L, "Due NY Spring Day", "TODO", "2026-03-09T03:59:59.999999Z", "America/New_York"),
                    task(12L, 2L, "Next NY Day", "TODO", "2026-03-09T04:00:00Z", "America/New_York")
            ));

            @SuppressWarnings("unchecked")
            List<TaskAiTools.AiQueryTaskDto> nyDueToday = (List<TaskAiTools.AiQueryTaskDto>) nyTools.queryTasks(
                    "2", null, null, null, true, null, null, null, null, null);
            assertEquals(List.of(11L), nyDueToday.stream().map(TaskAiTools.AiQueryTaskDto::id).toList());
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    private static TaskSummaryDto task(Long id, Long projectId, String title, String status, String dueDate, String zoneId) {
        return new TaskSummaryDto(
                id,
                projectId,
                null,
                null,
                title,
                null,
                status,
                "MEDIUM",
                2,
                null,
                null,
                null,
                dueDate,
                "2026-11-01T00:00:00Z",
                "2026-11-01T00:00:00Z",
                0L,
                zoneId
        );
    }
}
