package com.taskpilot.ai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.aiquery.dto.*;
import com.taskpilot.contracts.aiquery.port.out.*;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.contracts.user.port.out.UserNotificationQueryPort;

@ExtendWith(MockitoExtension.class)
class SmartQueryServiceTest {

    private static final long USER_ID = 1L;

    @Mock private ProjectInsightsPort projectInsightsPort;
    @Mock private TaskCommandPort taskCommandPort;
    @Mock private MemberAnalyticsPort memberAnalyticsPort;
    @Mock private SprintQueryPort sprintQueryPort;
    @Mock private TaskCommentQueryPort taskCommentQueryPort;
    @Mock private SkillPort skillPort;
    @Mock private UserNotificationQueryPort userNotificationQueryPort;

    @InjectMocks
    private SmartQueryService smartQueryService;

    @BeforeEach
    void setUp() {
        ToolExecutionContext.set(new ToolExecutionContext.Context(USER_ID, 100L, "test message"));
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    // Helper to create single-chain request
    private SmartQueryRequestDto req(List<SmartQueryRequestDto.QueryStep> steps) {
        return new SmartQueryRequestDto(List.of(new SmartQueryRequestDto.QueryChain(steps)));
    }

    // Helper to create two-chain request
    private SmartQueryRequestDto req(List<SmartQueryRequestDto.QueryStep> steps1, List<SmartQueryRequestDto.QueryStep> steps2) {
        return new SmartQueryRequestDto(List.of(
            new SmartQueryRequestDto.QueryChain(steps1),
            new SmartQueryRequestDto.QueryChain(steps2)
        ));
    }

    @Test
    void test1_SingleChain_1Step() {
        // Arrange
        ProjectOverviewDto p = new ProjectOverviewDto(10L, "TaskPilot", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");
        when(projectInsightsPort.getMyProjects(USER_ID)).thenReturn(List.of(p));

        SmartQueryRequestDto.QueryStep step = new SmartQueryRequestDto.QueryStep(
                "p", "projects", Map.of(), Map.of(), null, null, 10
        );
        SmartQueryRequestDto request = req(List.of(step));

        // Act
        SmartQueryResponseDto response = smartQueryService.execute(request, USER_ID);

        // Assert
        assertNotNull(response);
        assertTrue(response.errors().isEmpty());
        assertEquals(1, response.results().size());
        List<?> projects = (List<?>) response.results().get("p");
        assertEquals(1, projects.size());
        assertEquals(10L, ((ProjectOverviewDto) projects.get(0)).projectId());
    }

    @Test
    void test2_SingleChain_2Steps_WithRef() {
        // Arrange
        ProjectOverviewDto p = new ProjectOverviewDto(10L, "TaskPilot", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");
        ProjectMemberDto m = new ProjectMemberDto(20L, "Julia Design", "MEMBER", 90.0, "UI/UX");
        
        when(projectInsightsPort.getMyProjects(USER_ID)).thenReturn(List.of(p));
        when(projectInsightsPort.getProjectMembers(10L, USER_ID)).thenReturn(List.of(m));

        SmartQueryRequestDto.QueryStep step1 = new SmartQueryRequestDto.QueryStep(
                "p", "projects", Map.of(), Map.of(), null, null, 10
        );
        SmartQueryRequestDto.QueryStep step2 = new SmartQueryRequestDto.QueryStep(
                "m", "members", Map.of(), Map.of("projectId", "p"), null, null, 10
        );
        
        SmartQueryRequestDto request = req(List.of(step1, step2));

        // Act
        SmartQueryResponseDto response = smartQueryService.execute(request, USER_ID);

        // Assert
        assertNotNull(response);
        assertTrue(response.errors().isEmpty());
        assertEquals(2, response.results().size());
        List<?> members = (List<?>) response.results().get("m");
        assertEquals(1, members.size());
        assertEquals(20L, ((ProjectMemberDto) members.get(0)).memberId());
    }

    @Test
    void test3_SingleChain_3Steps_DeepNested() {
        // Arrange
        ProjectOverviewDto p = new ProjectOverviewDto(10L, "TaskPilot", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");
        SprintSummaryDto s = new SprintSummaryDto(30L, 10L, "Sprint 1", "Goal", "ACTIVE", "2026-06-01", "2026-06-15", "HEURISTIC");
        TaskSummaryDto t = new TaskSummaryDto(40L, 10L, null, 30L, "Task A", "Desc", "TODO", "HIGH", 3, null, null, "2026-06-15", "2026-06-10", "2026-06-10");

        when(projectInsightsPort.getMyProjects(USER_ID)).thenReturn(List.of(p));
        when(sprintQueryPort.getSprintsByProject(10L, USER_ID)).thenReturn(List.of(s));
        when(taskCommandPort.getTasksByProject(10L, USER_ID)).thenReturn(List.of(t));

        SmartQueryRequestDto.QueryStep step1 = new SmartQueryRequestDto.QueryStep("p", "projects", Map.of(), Map.of(), "$latest", null, 1);
        SmartQueryRequestDto.QueryStep step2 = new SmartQueryRequestDto.QueryStep("s", "sprints", Map.of("status", "ACTIVE"), Map.of("projectId", "p"), null, null, 1);
        SmartQueryRequestDto.QueryStep step3 = new SmartQueryRequestDto.QueryStep("t", "tasks", Map.of("unassignedOnly", "true"), Map.of("projectId", "p", "sprintId", "s"), null, null, 10);

        SmartQueryRequestDto request = req(List.of(step1, step2, step3));

        // Act
        SmartQueryResponseDto response = smartQueryService.execute(request, USER_ID);

        // Assert
        assertNotNull(response);
        assertTrue(response.errors().isEmpty());
        List<?> tasks = (List<?>) response.results().get("t");
        assertEquals(1, tasks.size());
        assertEquals(40L, ((TaskSummaryDto) tasks.get(0)).id());
    }

    @Test
    void test4_ParallelChains_Execution() {
        // Arrange
        ProjectOverviewDto p = new ProjectOverviewDto(10L, "TaskPilot", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");
        ProjectMemberDto m = new ProjectMemberDto(20L, "Julia Design", "MEMBER", 90.0, "UI/UX");
        MemberWorkloadDto w = new MemberWorkloadDto(20L, "Julia Design", 5, 2, 45);

        when(projectInsightsPort.getMyProjects(USER_ID)).thenReturn(List.of(p));
        when(projectInsightsPort.getProjectMembers(10L, USER_ID)).thenReturn(List.of(m));
        when(memberAnalyticsPort.getMemberWorkloadForProject(10L, USER_ID)).thenReturn(List.of(w));

        SmartQueryRequestDto.QueryStep chain1Step1 = new SmartQueryRequestDto.QueryStep("p1", "projects", Map.of(), Map.of(), null, null, 10);
        SmartQueryRequestDto.QueryStep chain1Step2 = new SmartQueryRequestDto.QueryStep("m", "members", Map.of(), Map.of("projectId", "p1"), null, null, 10);
        
        SmartQueryRequestDto.QueryStep chain2Step1 = new SmartQueryRequestDto.QueryStep("p2", "projects", Map.of(), Map.of(), null, null, 10);
        SmartQueryRequestDto.QueryStep chain2Step2 = new SmartQueryRequestDto.QueryStep("w", "workload", Map.of(), Map.of("projectId", "p2"), null, null, 10);

        SmartQueryRequestDto request = req(
                List.of(chain1Step1, chain1Step2),
                List.of(chain2Step1, chain2Step2)
        );

        // Act
        SmartQueryResponseDto response = smartQueryService.execute(request, USER_ID);

        // Assert
        assertNotNull(response);
        assertTrue(response.errors().isEmpty());
        assertEquals(4, response.results().size());
        assertTrue(response.results().containsKey("m"));
        assertTrue(response.results().containsKey("w"));
        assertEquals(2, response.chainStatuses().size());
    }

    @Test
    void test5_ChainFailFast() {
        // Arrange
        ProjectOverviewDto p = new ProjectOverviewDto(10L, "TaskPilot", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");
        
        when(projectInsightsPort.getMyProjects(USER_ID)).thenReturn(List.of(p));
        // Giả lập step 2 ném exception do lỗi database hoặc không tìm thấy member
        when(projectInsightsPort.getProjectMembers(10L, USER_ID)).thenThrow(new RuntimeException("Database timeout"));

        SmartQueryRequestDto.QueryStep step1 = new SmartQueryRequestDto.QueryStep("p", "projects", Map.of(), Map.of(), null, null, 10);
        SmartQueryRequestDto.QueryStep step2 = new SmartQueryRequestDto.QueryStep("m", "members", Map.of(), Map.of("projectId", "p"), null, null, 10);
        SmartQueryRequestDto.QueryStep step3 = new SmartQueryRequestDto.QueryStep("t", "tasks", Map.of(), Map.of("projectId", "p"), null, null, 10);

        SmartQueryRequestDto request = req(List.of(step1, step2, step3));

        // Act
        SmartQueryResponseDto response = smartQueryService.execute(request, USER_ID);

        // Assert
        assertNotNull(response);
        // Step 1 vẫn thành công
        assertTrue(response.results().containsKey("p"));
        // Step 2 bị lỗi
        assertTrue(response.errors().containsKey("m"));
        assertEquals("Database timeout", response.errors().get("m"));
        // Step 3 không được thực thi (fail-fast)
        assertFalse(response.results().containsKey("t"));
    }

    @Test
    void test6_CrossChainIsolation() {
        // Arrange
        ProjectOverviewDto p = new ProjectOverviewDto(10L, "TaskPilot", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");
        ProjectMemberDto m = new ProjectMemberDto(20L, "Julia Design", "MEMBER", 90.0, "UI/UX");

        // Chain 1 thành công
        when(projectInsightsPort.getMyProjects(USER_ID)).thenReturn(List.of(p));
        // Chain 2 bị lỗi ở step 2
        when(projectInsightsPort.getProjectMembers(10L, USER_ID)).thenThrow(new RuntimeException("Chain 2 error"));

        SmartQueryRequestDto.QueryStep chain1Step1 = new SmartQueryRequestDto.QueryStep("p1", "projects", Map.of(), Map.of(), null, null, 10);
        
        SmartQueryRequestDto.QueryStep chain2Step1 = new SmartQueryRequestDto.QueryStep("p2", "projects", Map.of(), Map.of(), null, null, 10);
        SmartQueryRequestDto.QueryStep chain2Step2 = new SmartQueryRequestDto.QueryStep("m2", "members", Map.of(), Map.of("projectId", "p2"), null, null, 10);

        SmartQueryRequestDto request = req(
                List.of(chain1Step1),
                List.of(chain2Step1, chain2Step2)
        );

        // Act
        SmartQueryResponseDto response = smartQueryService.execute(request, USER_ID);

        // Assert
        assertNotNull(response);
        // Chain 1 vẫn có kết quả
        assertTrue(response.results().containsKey("p1"));
        // Chain 2 chứa lỗi ở step 2
        assertTrue(response.errors().containsKey("m2"));
        // Dự án của chain 2 (step 1) vẫn trả về kết quả
        assertTrue(response.results().containsKey("p2"));
    }

    @Test
    void test7_Aggregate_MostMembers() {
        // Arrange
        ProjectOverviewDto p1 = new ProjectOverviewDto(10L, "Project 1", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");
        ProjectOverviewDto p2 = new ProjectOverviewDto(11L, "Project 2", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");

        when(projectInsightsPort.getMyProjects(USER_ID)).thenReturn(List.of(p1, p2));
        
        // P1 có 1 member, P2 có 2 members
        when(projectInsightsPort.getProjectMembers(10L, USER_ID)).thenReturn(List.of(mock(ProjectMemberDto.class)));
        when(projectInsightsPort.getProjectMembers(11L, USER_ID)).thenReturn(List.of(mock(ProjectMemberDto.class), mock(ProjectMemberDto.class)));

        SmartQueryRequestDto.QueryStep step = new SmartQueryRequestDto.QueryStep(
                "p", "projects", Map.of(), Map.of(), "$mostMembers", null, 1
        );
        SmartQueryRequestDto request = req(List.of(step));

        // Act
        SmartQueryResponseDto response = smartQueryService.execute(request, USER_ID);

        // Assert
        assertNotNull(response);
        List<?> projects = (List<?>) response.results().get("p");
        assertEquals(1, projects.size());
        // Dự án P2 (ID 11L) phải được chọn
        assertEquals(11L, ((ProjectOverviewDto) projects.get(0)).projectId());
    }

    @Test
    void test8_SortAndLimit() {
        // Arrange
        ProjectOverviewDto p1 = new ProjectOverviewDto(10L, "Z-Project", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");
        ProjectOverviewDto p2 = new ProjectOverviewDto(11L, "A-Project", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");
        ProjectOverviewDto p3 = new ProjectOverviewDto(12L, "M-Project", "Desc", "ACTIVE", "MANAGER", "2026-06-01", "2026-06-30", "2026-06-01T12:00:00Z");

        when(projectInsightsPort.getMyProjects(USER_ID)).thenReturn(List.of(p1, p2, p3));

        // Sort theo name ASC, limit 2 (Kỳ vọng: A-Project, M-Project)
        SmartQueryRequestDto.QueryStep step1 = new SmartQueryRequestDto.QueryStep(
                "p_asc", "projects", Map.of(), Map.of(), null, "name ASC", 2
        );
        
        // Sort theo name DESC, limit 1 (Kỳ vọng: Z-Project)
        SmartQueryRequestDto.QueryStep step2 = new SmartQueryRequestDto.QueryStep(
                "p_desc", "projects", Map.of(), Map.of(), null, "name DESC", 1
        );

        SmartQueryRequestDto request = req(List.of(step1), List.of(step2));

        // Act
        SmartQueryResponseDto response = smartQueryService.execute(request, USER_ID);

        // Assert
        assertNotNull(response);
        List<?> ascResult = (List<?>) response.results().get("p_asc");
        assertEquals(2, ascResult.size());
        assertEquals("A-Project", ((ProjectOverviewDto) ascResult.get(0)).name());
        assertEquals("M-Project", ((ProjectOverviewDto) ascResult.get(1)).name());

        List<?> descResult = (List<?>) response.results().get("p_desc");
        assertEquals(1, descResult.size());
        assertEquals("Z-Project", ((ProjectOverviewDto) descResult.get(0)).name());
    }
}
