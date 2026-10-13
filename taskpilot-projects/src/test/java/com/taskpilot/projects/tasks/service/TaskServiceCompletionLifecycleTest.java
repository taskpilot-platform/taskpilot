package com.taskpilot.projects.tasks.service;

import com.taskpilot.contracts.assignment.event.TaskCompletedLifecycleEvent;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.PriorityLevel;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.*;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.tasks.dto.KanbanMoveRequest;
import com.taskpilot.projects.tasks.dto.TaskDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskServiceCompletionLifecycleTest {

    private TaskRepository taskRepository;
    private ProjectSecurityService projectSecurityService;
    private ProjectMemberRepository projectMemberRepository;
    private UserIdentityPort userIdentityPort;
    private SkillPort skillPort;
    private UserPort userPort;
    private ApplicationEventPublisher eventPublisher;
    private LabelRepository labelRepository;
    private TaskLabelRepository taskLabelRepository;
    private TaskRequiredSkillRepository taskRequiredSkillRepository;
    private SprintRepository sprintRepository;
    private TaskDtoMapper taskDtoMapper;

    private TaskService taskService;

    private static final Long PROJECT_ID = 10L;
    private static final Long TASK_ID = 76L;
    private static final Long USER_ID = 1L;
    private static final Long ASSIGNEE_ID = 42L;
    private static final String USER_EMAIL = "pm@taskpilot.io";

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        projectSecurityService = mock(ProjectSecurityService.class);
        projectMemberRepository = mock(ProjectMemberRepository.class);
        userIdentityPort = mock(UserIdentityPort.class);
        skillPort = mock(SkillPort.class);
        userPort = mock(UserPort.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        labelRepository = mock(LabelRepository.class);
        taskLabelRepository = mock(TaskLabelRepository.class);
        taskRequiredSkillRepository = mock(TaskRequiredSkillRepository.class);
        sprintRepository = mock(SprintRepository.class);
        taskDtoMapper = mock(TaskDtoMapper.class);

        taskService = new TaskService(
                taskRepository,
                projectSecurityService,
                projectMemberRepository,
                userIdentityPort,
                skillPort,
                userPort,
                eventPublisher,
                labelRepository,
                taskLabelRepository,
                taskRequiredSkillRepository,
                sprintRepository,
                taskDtoMapper
        );

        when(userIdentityPort.findByEmail(USER_EMAIL))
                .thenReturn(Optional.of(new UserIdentityDto(USER_ID, USER_EMAIL)));

        when(taskDtoMapper.mapToDtoWithLabels(any(TaskEntity.class)))
                .thenAnswer(inv -> TaskDto.fromEntity(inv.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("Transitioning to DONE sets completedAt and publishes TaskCompletedLifecycleEvent")
    void testTransitionToDone_setsCompletedAtAndPublishesEvent() {
        Instant dueDate = Instant.parse("2026-10-15T18:00:00Z");
        TaskEntity existingTask = TaskEntity.builder()
                .id(TASK_ID)
                .projectId(PROJECT_ID)
                .title("Implementation Task")
                .status(TaskStatus.IN_PROGRESS)
                .assigneeId(ASSIGNEE_ID)
                .reporterId(USER_ID)
                .dueDate(dueDate)
                .completedAt(null)
                .build();

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(existingTask));

        UpdateTaskRequest request = new UpdateTaskRequest(
                "Implementation Task", null, TaskStatus.DONE, null, null, null, null, null, null, null, null
        );

        TaskDto updated = taskService.updateTask(TASK_ID, request, USER_EMAIL);

        assertNotNull(existingTask.getCompletedAt());
        assertEquals(TaskStatus.DONE, existingTask.getStatus());
        assertNotNull(updated.completedAt());

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        Object published = eventCaptor.getValue();
        assertInstanceOf(TaskCompletedLifecycleEvent.class, published);
        TaskCompletedLifecycleEvent event = (TaskCompletedLifecycleEvent) published;
        assertEquals(TASK_ID, event.taskId());
        assertEquals(PROJECT_ID, event.projectId());
        assertEquals(ASSIGNEE_ID, event.assigneeId());
        assertEquals(existingTask.getCompletedAt(), event.completedAt());
        assertEquals(dueDate, event.dueDate());
    }

    @Test
    @DisplayName("Transitioning from DONE to IN_PROGRESS clears completedAt to null and publishes no completion event")
    void testTransitionFromDoneToInProgress_clearsCompletedAt() {
        Instant pastCompletedAt = Instant.parse("2026-10-10T12:00:00Z");
        TaskEntity existingTask = TaskEntity.builder()
                .id(TASK_ID)
                .projectId(PROJECT_ID)
                .title("Reopened Task")
                .status(TaskStatus.DONE)
                .assigneeId(ASSIGNEE_ID)
                .reporterId(USER_ID)
                .dueDate(Instant.parse("2026-10-15T18:00:00Z"))
                .completedAt(pastCompletedAt)
                .build();

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(existingTask));

        UpdateTaskRequest request = new UpdateTaskRequest(
                "Reopened Task", null, TaskStatus.IN_PROGRESS, null, null, null, null, null, null, null, null
        );

        TaskDto updated = taskService.updateTask(TASK_ID, request, USER_EMAIL);

        assertNull(existingTask.getCompletedAt());
        assertEquals(TaskStatus.IN_PROGRESS, existingTask.getStatus());
        assertNull(updated.completedAt());

        verify(eventPublisher, never()).publishEvent(any(TaskCompletedLifecycleEvent.class));
    }

    @Test
    @DisplayName("Kanban move to DONE sets completedAt and publishes TaskCompletedLifecycleEvent")
    void testMoveTaskKanbanToDone_setsCompletedAtAndPublishesEvent() {
        TaskEntity existingTask = TaskEntity.builder()
                .id(TASK_ID)
                .projectId(PROJECT_ID)
                .title("Kanban Task")
                .status(TaskStatus.TODO)
                .assigneeId(ASSIGNEE_ID)
                .reporterId(USER_ID)
                .dueDate(Instant.parse("2026-10-20T18:00:00Z"))
                .completedAt(null)
                .build();

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(existingTask));

        KanbanMoveRequest request = new KanbanMoveRequest(TaskStatus.DONE, 1000f);
        TaskDto updated = taskService.moveTaskKanban(TASK_ID, request, USER_EMAIL);

        assertNotNull(existingTask.getCompletedAt());
        assertEquals(TaskStatus.DONE, existingTask.getStatus());
        assertNotNull(updated.completedAt());

        verify(eventPublisher).publishEvent(any(TaskCompletedLifecycleEvent.class));
    }

    @Test
    @DisplayName("Redundant update with DONE status preserves existing completedAt and does not publish duplicate event")
    void testRedundantDoneTransition_doesNotPublishDuplicateEventAndPreservesCompletedAt() {
        Instant originalCompletedAt = Instant.parse("2026-10-10T14:00:00Z");
        TaskEntity existingTask = TaskEntity.builder()
                .id(TASK_ID)
                .projectId(PROJECT_ID)
                .title("Already Done Task")
                .status(TaskStatus.DONE)
                .assigneeId(ASSIGNEE_ID)
                .reporterId(USER_ID)
                .dueDate(Instant.parse("2026-10-15T18:00:00Z"))
                .completedAt(originalCompletedAt)
                .build();

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(existingTask));

        UpdateTaskRequest request = new UpdateTaskRequest(
                "Updated Title", null, TaskStatus.DONE, null, null, null, null, null, null, null, null
        );

        TaskDto updated = taskService.updateTask(TASK_ID, request, USER_EMAIL);

        assertEquals(originalCompletedAt, existingTask.getCompletedAt());
        assertEquals(originalCompletedAt, updated.completedAt());
        assertEquals(TaskStatus.DONE, existingTask.getStatus());

        // Crucial invariant: redundant DONE transition must NEVER publish a second lifecycle event
        verify(eventPublisher, never()).publishEvent(any(TaskCompletedLifecycleEvent.class));
    }
}
