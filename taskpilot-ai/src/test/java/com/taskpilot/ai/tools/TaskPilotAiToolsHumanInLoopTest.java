package com.taskpilot.ai.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.aiquery.dto.SprintSummaryDto;
import com.taskpilot.contracts.aiquery.dto.ProjectMemberDto;
import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;
import com.taskpilot.contracts.aiquery.port.out.MemberAnalyticsPort;
import com.taskpilot.contracts.aiquery.port.out.ProjectInsightsPort;
import com.taskpilot.contracts.aiquery.port.out.SprintQueryPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.contracts.aiquery.port.out.TaskCommentQueryPort;
import com.taskpilot.contracts.skill.port.out.SkillPort;

@ExtendWith(MockitoExtension.class)
class TaskPilotAiToolsHumanInLoopTest {

    private static final long USER_ID = 16L;
    private static final long SESSION_ID = 46L;

    @Mock
    private ProjectMemberPort projectMemberPort;

    @Mock
    private ProjectInsightsPort projectInsightsPort;

    @Mock
    private AutoAssignmentService autoAssignmentService;

    @Mock
    private MemberAnalyticsPort memberAnalyticsPort;

    @Mock
    private TaskCommandPort taskCommandPort;

    @Mock
    private TaskCommentQueryPort taskCommentQueryPort;

    @Mock
    private SprintQueryPort sprintQueryPort;

    @Mock
    private SkillPort skillPort;

    private TaskPilotAiTools tools;

    @BeforeEach
    void setUp() {
        tools = new TaskPilotAiTools(
                autoAssignmentService,
                projectMemberPort,
                projectInsightsPort,
                memberAnalyticsPort,
                taskCommandPort,
                taskCommentQueryPort,
                sprintQueryPort,
                skillPort,
                new PendingAiActionService());
        ToolExecutionContext.set(new ToolExecutionContext.Context(USER_ID, SESSION_ID, "initial request"));
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void assignTaskToMemberWaitsForHumanConfirmationBeforeWriting() {
        when(taskCommandPort.assignTaskToMember(75L, 16L, "test", USER_ID, false))
                .thenReturn(TaskAssignmentResultDto.success(75L, 16L, "test"));

        ConfirmationRequiredDto pending = assertPending(tools.assignTaskToMember(75L, 16L, "test"),
                "assignTaskToMember");

        verify(taskCommandPort, never()).assignTaskToMember(any(), any(), any(), any(), eq(false));

        Object result = confirm(pending.actionId());

        TaskAssignmentResultDto assignment = assertInstanceOf(TaskAssignmentResultDto.class, result);
        assertEquals("SUCCESS", assignment.status());
        verify(taskCommandPort).assignTaskToMember(75L, 16L, "test", USER_ID, false);
    }

    @Test
    void assignTaskToMemberByNameUsesUserSpecifiedMemberInsteadOfRecommendation() {
        when(taskCommandPort.getTaskDetails(68L, USER_ID))
                .thenReturn(new TaskDetailDto(68L, 1L, "Sub task 3", "", "TODO", "MEDIUM", 1,
                        "Java", null, null, null));
        when(projectInsightsPort.getProjectMembers(1L, USER_ID))
                .thenReturn(List.of(
                        new ProjectMemberDto(18L, "FuTie Neith", "MEMBER", 0.7, "Java"),
                        new ProjectMemberDto(10L, "Julia Design", "MEMBER", 0.85, "Java")));
        when(taskCommandPort.assignTaskToMember(eq(68L), eq(10L), any(), eq(USER_ID), eq(false)))
                .thenReturn(TaskAssignmentResultDto.success(68L, 10L, "selected"));

        ConfirmationRequiredDto pending = assertPending(
                tools.assignTaskToMemberByName(68L, "Julia Design", "User requested Julia"),
                "assignTaskToMember");

        assertTrue(pending.summary().contains("Julia Design"));
        verify(autoAssignmentService, never()).recommendCandidates(anyLong(), any(), anyInt(), anyLong());
        verify(taskCommandPort, never()).assignTaskToMember(anyLong(), anyLong(), any(), anyLong(), eq(false));

        Object result = confirm(pending.actionId());

        TaskAssignmentResultDto assignment = assertInstanceOf(TaskAssignmentResultDto.class, result);
        assertEquals(10L, assignment.assignedTo());
        verify(taskCommandPort).assignTaskToMember(68L, 10L, "User requested Julia", USER_ID, false);
    }

    @Test
    void recommendAndAssignTaskWaitsForHumanConfirmationBeforeWriting() {
        when(taskCommandPort.getTaskDetails(76L, USER_ID))
                .thenReturn(new TaskDetailDto(76L, 8L, "Test task", "", "TODO", "MEDIUM", 5,
                        "Spring Boot", null, null, null));
        when(autoAssignmentService.recommendCandidates(8L, List.of("Spring Boot"), 5, USER_ID))
                .thenReturn(AutoAssignmentResponse.builder()
                        .projectId(8L)
                        .requiredSkills(List.of("Spring Boot"))
                        .candidates(List.of(CandidateScore.builder()
                                .userId(16L)
                                .fullName("Admin")
                                .totalScore(0.9)
                                .build()))
                        .aiExplanation("Best candidate")
                        .build());
        when(taskCommandPort.assignTaskToMember(eq(76L), eq(16L), any(), eq(USER_ID), eq(false)))
                .thenReturn(TaskAssignmentResultDto.success(76L, 16L, "selected"));

        ConfirmationRequiredDto pending = assertPending(
                tools.recommendAndAssignTask(76L, null, null, null, null),
                "recommendAndAssignTask");

        verify(taskCommandPort, never()).assignTaskToMember(eq(76L), eq(16L), any(), eq(USER_ID), eq(false));

        Object result = confirm(pending.actionId());

        assertTrue(result.toString().contains("selectedMemberName=Admin"));
        verify(taskCommandPort).assignTaskToMember(eq(76L), eq(16L), any(), eq(USER_ID), eq(false));
    }

    @Test
    void updateTaskStatusWaitsForHumanConfirmationBeforeWriting() {
        TaskSummaryDto updated = task(75L, "IN_PROGRESS");
        when(taskCommandPort.updateTaskStatus(75L, "IN_PROGRESS", USER_ID)).thenReturn(updated);

        ConfirmationRequiredDto pending = assertPending(tools.updateTaskStatus(75L, "IN_PROGRESS"),
                "updateTaskStatus");

        verify(taskCommandPort, never()).updateTaskStatus(any(), any(), any());

        assertEquals(updated, confirm(pending.actionId()));
        verify(taskCommandPort).updateTaskStatus(75L, "IN_PROGRESS", USER_ID);
    }

    @Test
    void updateTaskRequiredSkillsWaitsForHumanConfirmationBeforeWriting() {
        TaskSummaryDto updated = task(75L, "TODO");
        when(taskCommandPort.updateTaskRequiredSkills(75L, "Java", USER_ID)).thenReturn(updated);

        ConfirmationRequiredDto pending = assertPending(tools.updateTaskRequiredSkills(75L, "Java"),
                "updateTaskRequiredSkills");

        verify(taskCommandPort, never()).updateTaskRequiredSkills(any(), any(), any());

        assertEquals(updated, confirm(pending.actionId()));
        verify(taskCommandPort).updateTaskRequiredSkills(75L, "Java", USER_ID);
    }

    @Test
    void recommendAndAssignTaskPersistsProvidedSkillsWhenTaskHasNone() {
        when(taskCommandPort.getTaskDetails(76L, USER_ID))
                .thenReturn(new TaskDetailDto(76L, 8L, "Test task", "", "TODO", "MEDIUM", 5,
                        "", null, null, null));
        when(autoAssignmentService.recommendCandidates(8L, List.of("Java"), 5, USER_ID))
                .thenReturn(AutoAssignmentResponse.builder()
                        .projectId(8L)
                        .requiredSkills(List.of("Java"))
                        .candidates(List.of(CandidateScore.builder()
                                .userId(16L)
                                .fullName("Admin")
                                .totalScore(0.9)
                                .build()))
                        .build());
        when(taskCommandPort.assignTaskToMember(eq(76L), eq(16L), any(), eq(USER_ID), eq(false)))
                .thenReturn(TaskAssignmentResultDto.success(76L, 16L, "selected"));

        ConfirmationRequiredDto pending = assertPending(
                tools.recommendAndAssignTask(76L, null, "Java", 5, null),
                "recommendAndAssignTask");

        verify(taskCommandPort, never()).updateTaskRequiredSkills(any(), any(), any());
        verify(taskCommandPort, never()).assignTaskToMember(eq(76L), eq(16L), any(), eq(USER_ID), eq(false));

        Object result = confirm(pending.actionId());

        assertTrue(result.toString().contains("selectedMemberName=Admin"));
        verify(taskCommandPort).updateTaskRequiredSkills(76L, "Java", USER_ID);
        verify(taskCommandPort).assignTaskToMember(eq(76L), eq(16L), any(), eq(USER_ID), eq(false));
    }

    @Test
    void createTaskWaitsForHumanConfirmationBeforeWriting() {
        TaskSummaryDto created = task(90L, "TODO");
        when(taskCommandPort.createTask(8L, "AI test", "desc", "MEDIUM", null, null, null, 3, null, null,
                null, null, null, USER_ID))
                .thenReturn(created);

        ConfirmationRequiredDto pending = assertPending(
                tools.createTask(8L, "AI test", "MEDIUM", "desc", null, null, null, 3, null, null, null, null, null),
                "createTask");

        verify(taskCommandPort, never()).createTask(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());

        assertEquals(created, confirm(pending.actionId()));
        verify(taskCommandPort).createTask(8L, "AI test", "desc", "MEDIUM", null, null, null, 3, null, null,
                null, null, null, USER_ID);
    }

    @Test
    void sprintWriteToolsWaitForHumanConfirmationBeforeWriting() {
        SprintSummaryDto sprint = new SprintSummaryDto(31L, 8L, "Sprint test", "goal", "PLANNED",
                "2026-05-27", "2026-06-03", "BALANCED");
        when(sprintQueryPort.createSprint(8L, "Sprint test", "2026-05-27", "2026-06-03", "goal", USER_ID))
                .thenReturn(sprint);
        when(sprintQueryPort.startSprint(8L, 31L, USER_ID)).thenReturn(sprint);
        when(sprintQueryPort.completeSprint(8L, 31L, USER_ID)).thenReturn(sprint);
        when(sprintQueryPort.assignTaskToSprint(75L, 31L, USER_ID)).thenReturn(task(75L, "TODO"));

        ConfirmationRequiredDto create = assertPending(
                tools.createSprint(8L, "Sprint test", "2026-05-27", "2026-06-03", "goal"),
                "createSprint");
        ConfirmationRequiredDto start = assertPending(tools.startSprint(8L, 31L), "startSprint");
        ConfirmationRequiredDto complete = assertPending(tools.completeSprint(8L, 31L), "completeSprint");
        ConfirmationRequiredDto assign = assertPending(tools.assignTaskToSprint(75L, 31L), "assignTaskToSprint");

        verify(sprintQueryPort, never()).createSprint(any(), any(), any(), any(), any(), any());
        verify(sprintQueryPort, never()).startSprint(any(), any(), any());
        verify(sprintQueryPort, never()).completeSprint(any(), any(), any());
        verify(sprintQueryPort, never()).assignTaskToSprint(any(), any(), any());

        assertEquals(sprint, confirm(create.actionId()));
        assertEquals(sprint, confirm(start.actionId()));
        assertEquals(sprint, confirm(complete.actionId()));
        assertEquals(task(75L, "TODO"), confirm(assign.actionId()));

        verify(sprintQueryPort).createSprint(8L, "Sprint test", "2026-05-27", "2026-06-03", "goal", USER_ID);
        verify(sprintQueryPort).startSprint(8L, 31L, USER_ID);
        verify(sprintQueryPort).completeSprint(8L, 31L, USER_ID);
        verify(sprintQueryPort).assignTaskToSprint(75L, 31L, USER_ID);
    }

    @Test
    void additionalProjectWriteToolsWaitForHumanConfirmationBeforeWriting() {
        assertPending(tools.updateTask(75L, "New title", "desc", "REVIEW", "HIGH", 2f,
                List.of(4L), 5, List.of(7L), 18L, "2026-06-01", "2026-06-08"), "updateTask");
        assertPending(tools.deleteTask(75L), "deleteTask");
        assertPending(tools.moveTaskKanban(75L, "DONE", 3f), "moveTaskKanban");
        assertPending(tools.createTaskComment(75L, "Looks good", null, List.of(18L)), "createTaskComment");
        assertPending(tools.updateTaskComment(75L, 30L, "Updated", List.of()), "updateTaskComment");
        assertPending(tools.deleteTaskComment(75L, 30L), "deleteTaskComment");
        assertPending(tools.createProjectLabel(8L, "urgent", "#EF4444"), "createProjectLabel");
        assertPending(tools.deleteProjectLabel(8L, 4L), "deleteProjectLabel");
        assertPending(tools.updateSprint(8L, 31L, "Sprint renamed", null, null, "new goal"), "updateSprint");
        assertPending(tools.deleteSprint(8L, 31L), "deleteSprint");

        verify(taskCommandPort, never()).updateTask(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(taskCommandPort, never()).deleteTask(any(), any());
        verify(taskCommandPort, never()).moveTaskKanban(any(), any(), any(), any());
        verify(taskCommentQueryPort, never()).createTaskComment(any(), any(), any(), any(), any());
        verify(taskCommentQueryPort, never()).updateTaskComment(any(), any(), any(), any(), any());
        verify(taskCommentQueryPort, never()).deleteTaskComment(any(), any(), any());
        verify(projectInsightsPort, never()).createProjectLabel(any(), any(), any(), any());
        verify(projectInsightsPort, never()).deleteProjectLabel(any(), any(), any());
        verify(sprintQueryPort, never()).updateSprint(any(), any(), any(), any(), any(), any(), any());
        verify(sprintQueryPort, never()).deleteSprint(any(), any(), any());
    }

    private ConfirmationRequiredDto assertPending(Object result, String toolName) {
        ConfirmationRequiredDto pending = assertInstanceOf(ConfirmationRequiredDto.class, result);
        assertTrue(pending.confirmationRequired());
        assertEquals(toolName, pending.toolName());
        return pending;
    }

    private Object confirm(String actionId) {
        ToolExecutionContext.set(new ToolExecutionContext.Context(
                USER_ID,
                SESSION_ID,
                "CONFIRM_ACTION " + actionId + " - xac nhan thuc hien"));
        return tools.confirmPendingAction(actionId);
    }

    private TaskSummaryDto task(Long id, String status) {
        return new TaskSummaryDto(id, 8L, null, null, "AI test", "desc", status,
                "MEDIUM", 3, null, null, null, null, null);
    }
}
