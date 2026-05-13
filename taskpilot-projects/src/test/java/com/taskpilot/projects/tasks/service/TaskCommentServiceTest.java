package com.taskpilot.projects.tasks.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.taskpilot.contracts.user.dto.NotificationTypeDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.user.port.out.UserNotificationPort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.CommentEntity;
import com.taskpilot.projects.common.entity.CommentMentionEntity;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.repository.CommentMentionRepository;
import com.taskpilot.projects.common.repository.CommentRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.tasks.dto.CreateTaskCommentRequest;
import com.taskpilot.projects.tasks.dto.UpdateTaskCommentRequest;

@ExtendWith(MockitoExtension.class)
class TaskCommentServiceTest {

    private static final String EMAIL = "actor@example.com";
    private static final Long PROJECT_ID = 10L;
    private static final Long TASK_ID = 20L;
    private static final Long COMMENT_ID = 30L;
    private static final Long ACTOR_ID = 1L;

    @Mock
    private CommentRepository commentRepository;
    @Mock
    private CommentMentionRepository commentMentionRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private UserIdentityPort userIdentityPort;
    @Mock
    private UserProfilePort userProfilePort;
    @Mock
    private UserNotificationPort userNotificationPort;
    @Mock
    private TaskCommentRealtimeService realtimeService;

    private TaskCommentService service;

    @BeforeEach
    void setUp() {
        service = new TaskCommentService(
                commentRepository,
                commentMentionRepository,
                taskRepository,
                projectRepository,
                projectMemberRepository,
                userIdentityPort,
                userProfilePort,
                userNotificationPort,
                realtimeService);
    }

    @Test
    void getCommentsRejectsNonProjectMember() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task()));
        when(userIdentityPort.findByEmail(EMAIL)).thenReturn(Optional.of(new UserIdentityDto(ACTOR_ID, EMAIL)));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, ACTOR_ID)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getComments(TASK_ID, EMAIL));

        assertEquals(403, ex.getStatus());
    }

    @Test
    void createCommentNotifiesMentionsAndTaskParticipantsWithoutDuplicates() {
        TaskEntity task = task();
        task.setAssigneeId(2L);
        task.setReporterId(3L);

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(userIdentityPort.findByEmail(EMAIL)).thenReturn(Optional.of(new UserIdentityDto(ACTOR_ID, EMAIL)));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, ACTOR_ID)).thenReturn(true);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(ProjectEntity.ProjectStatus.ACTIVE)));
        when(projectMemberRepository.findMembers(PROJECT_ID)).thenReturn(members(1L, 2L, 3L, 4L, 5L));
        when(commentRepository.save(any(CommentEntity.class))).thenAnswer(invocation -> {
            CommentEntity comment = invocation.getArgument(0);
            comment.setId(COMMENT_ID);
            comment.setCreatedAt(Instant.parse("2026-05-13T00:00:00Z"));
            comment.setUpdatedAt(Instant.parse("2026-05-13T00:00:00Z"));
            return comment;
        });
        when(commentMentionRepository.findByCommentIdIn(anyCollection())).thenReturn(mentions(2L, 5L));
        when(userProfilePort.findLiteByIds(anySet())).thenAnswer(invocation -> profiles(invocation.getArgument(0)));
        when(commentRepository.findParticipantUserIdsByTaskId(TASK_ID)).thenReturn(Set.of(ACTOR_ID, 4L));

        service.createComment(TASK_ID, new CreateTaskCommentRequest(" Please review ", Set.of(2L, 5L)), EMAIL);

        ArgumentCaptor<SystemNotificationCommandDto> captor = ArgumentCaptor
                .forClass(SystemNotificationCommandDto.class);
        verify(userNotificationPort, org.mockito.Mockito.times(4)).createNotification(captor.capture());

        List<SystemNotificationCommandDto> commands = captor.getAllValues();
        assertTrue(hasNotification(commands, 2L, NotificationTypeDto.MENTION));
        assertTrue(hasNotification(commands, 5L, NotificationTypeDto.MENTION));
        assertTrue(hasNotification(commands, 3L, NotificationTypeDto.COMMENT));
        assertTrue(hasNotification(commands, 4L, NotificationTypeDto.COMMENT));
        assertFalse(commands.stream().anyMatch(command -> command.targetUserId().equals(ACTOR_ID)));
        verify(realtimeService).publishCreated(any());
    }

    @Test
    void updateCommentOnlyNotifiesNewMentions() {
        CommentEntity comment = comment(ACTOR_ID);

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task()));
        when(commentRepository.findByIdAndTaskId(COMMENT_ID, TASK_ID)).thenReturn(Optional.of(comment));
        when(userIdentityPort.findByEmail(EMAIL)).thenReturn(Optional.of(new UserIdentityDto(ACTOR_ID, EMAIL)));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, ACTOR_ID)).thenReturn(true);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(ProjectEntity.ProjectStatus.ACTIVE)));
        when(commentMentionRepository.findByCommentId(COMMENT_ID)).thenReturn(mentions(2L));
        when(projectMemberRepository.findMembers(PROJECT_ID)).thenReturn(members(1L, 2L, 3L));
        when(commentRepository.save(any(CommentEntity.class))).thenReturn(comment);
        when(commentMentionRepository.findByCommentIdIn(anyCollection())).thenReturn(mentions(2L, 3L));
        when(userProfilePort.findLiteByIds(anySet())).thenAnswer(invocation -> profiles(invocation.getArgument(0)));

        service.updateComment(TASK_ID, COMMENT_ID,
                new UpdateTaskCommentRequest("Updated", Set.of(2L, 3L)), EMAIL);

        ArgumentCaptor<SystemNotificationCommandDto> captor = ArgumentCaptor
                .forClass(SystemNotificationCommandDto.class);
        verify(userNotificationPort).createNotification(captor.capture());

        SystemNotificationCommandDto command = captor.getValue();
        assertEquals(3L, command.targetUserId());
        assertEquals(NotificationTypeDto.MENTION, command.type());
        verify(realtimeService).publishUpdated(any());
    }

    private TaskEntity task() {
        TaskEntity task = TaskEntity.builder()
                .projectId(PROJECT_ID)
                .title("Implement comments")
                .build();
        task.setId(TASK_ID);
        return task;
    }

    private ProjectEntity project(ProjectEntity.ProjectStatus status) {
        ProjectEntity project = ProjectEntity.builder()
                .name("TaskPilot")
                .status(status)
                .build();
        project.setId(PROJECT_ID);
        return project;
    }

    private CommentEntity comment(Long authorId) {
        CommentEntity comment = CommentEntity.builder()
                .taskId(TASK_ID)
                .userId(authorId)
                .content("Original")
                .build();
        comment.setId(COMMENT_ID);
        comment.setCreatedAt(Instant.parse("2026-05-13T00:00:00Z"));
        comment.setUpdatedAt(Instant.parse("2026-05-13T00:00:00Z"));
        return comment;
    }

    private List<ProjectMemberEntity> members(Long... userIds) {
        return java.util.Arrays.stream(userIds)
                .map(userId -> ProjectMemberEntity.builder()
                        .projectId(PROJECT_ID)
                        .userId(userId)
                        .role(ProjectMemberEntity.MemberRole.MEMBER)
                        .build())
                .toList();
    }

    private List<CommentMentionEntity> mentions(Long... userIds) {
        return java.util.Arrays.stream(userIds)
                .map(userId -> new CommentMentionEntity(
                        new CommentMentionEntity.CommentMentionId(COMMENT_ID, userId),
                        COMMENT_ID,
                        userId,
                        Instant.parse("2026-05-13T00:00:00Z")))
                .toList();
    }

    private List<UserProfileLiteDto> profiles(Set<Long> userIds) {
        return userIds.stream()
                .map(userId -> new UserProfileLiteDto(userId, "User " + userId))
                .toList();
    }

    private boolean hasNotification(List<SystemNotificationCommandDto> commands, Long targetUserId,
            NotificationTypeDto type) {
        return commands.stream()
                .anyMatch(command -> command.targetUserId().equals(targetUserId)
                        && command.type() == type);
    }
}
