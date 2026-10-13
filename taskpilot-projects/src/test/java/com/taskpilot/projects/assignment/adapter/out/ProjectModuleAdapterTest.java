package com.taskpilot.projects.assignment.adapter.out;

import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProjectModuleAdapterTest {

    private ProjectMemberRepository projectMemberRepository;
    private ProjectRepository projectRepository;
    private TaskRepository taskRepository;
    private ProjectModuleAdapter adapter;

    @BeforeEach
    void setUp() {
        projectMemberRepository = mock(ProjectMemberRepository.class);
        projectRepository = mock(ProjectRepository.class);
        taskRepository = mock(TaskRepository.class);
        adapter = new ProjectModuleAdapter(projectMemberRepository, projectRepository, taskRepository);
    }

    @Test
    @DisplayName("countActiveAssignedTasksByProject returns empty map when projectId is null")
    void countActiveAssignedTasksByProject_nullProjectId_returnsEmpty() {
        Map<Long, Integer> result = adapter.countActiveAssignedTasksByProject(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verifyNoInteractions(taskRepository);
    }

    @Test
    @DisplayName("countActiveAssignedTasksByProject queries taskRepository excluding DONE status")
    void countActiveAssignedTasksByProject_queriesAndMapsRows() {
        Long projectId = 42L;
        List<Object[]> queryRows = List.of(
                new Object[]{101L, 3L},
                new Object[]{102L, 1L}
        );

        when(taskRepository.countActiveAssignedTasksByProject(eq(projectId), eq(Set.of(TaskStatus.DONE))))
                .thenReturn(queryRows);

        Map<Long, Integer> result = adapter.countActiveAssignedTasksByProject(projectId);

        assertEquals(2, result.size());
        assertEquals(3, result.get(101L));
        assertEquals(1, result.get(102L));
        assertEquals(3, adapter.countActiveAssignedTasks(projectId, 101L));
        assertEquals(1, adapter.countActiveAssignedTasks(projectId, 102L));
        assertEquals(0, adapter.countActiveAssignedTasks(projectId, 999L));
    }
}
