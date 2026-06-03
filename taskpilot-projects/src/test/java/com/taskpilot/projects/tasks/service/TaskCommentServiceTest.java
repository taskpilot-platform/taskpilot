package com.taskpilot.projects.tasks.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.taskpilot.contracts.user.dto.NotificationTypeDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.user.port.out.UserNotificationPort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
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
import com.taskpilot.projects.tasks.dto.CommentSearchResultDto;
import com.taskpilot.projects.tasks.dto.CreateTaskCommentRequest;
import com.taskpilot.projects.tasks.dto.TaskCommentDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskCommentRequest;

@ExtendWith(MockitoExtension.class)
class TaskCommentServiceTest {

    private static final String EMAIL = "actor@example.com";
    private static final Long PROJECT_ID = 10L;
    private static final Long TASK_ID = 20L;
    private static final Long COMMENT_ID = 30L;
    private static final Long PARENT_COMMENT_ID = 40L;
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
    private UserPort userPort;
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
                userPort,
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

        service.createComment(TASK_ID, new CreateTaskCommentRequest(" Please review ", null, Set.of(2L, 5L)), EMAIL);

        ArgumentCaptor<SystemNotificationCommandDto> captor = ArgumentCaptor
                .forClass(SystemNotificationCommandDto.class);
        verify(userNotificationPort, org.mockito.Mockito.times(4)).createNotification(captor.capture());

        List<SystemNotificationCommandDto> commands = captor.getAllValues();
        assertTrue(hasNotification(commands, 2L, NotificationTypeDto.MENTION));
        assertTrue(hasNotification(commands, 5L, NotificationTypeDto.MENTION));
        assertTrue(hasNotification(commands, 3L, NotificationTypeDto.COMMENT));
        assertTrue(hasNotification(commands, 4L, NotificationTypeDto.COMMENT));
        assertTrue(commands.stream().allMatch(command -> command.linkAction()
                .equals("/tasks?taskId=" + TASK_ID + "&commentId=" + COMMENT_ID)));
        assertFalse(commands.stream().anyMatch(command -> command.targetUserId().equals(ACTOR_ID)));
        verify(realtimeService).publishCreated(any());
    }

    @Test
    void createReplyToCommentInSameTaskNotifiesParentAuthorWithDeepLink() {
        TaskEntity task = task();
        CommentEntity parent = comment(2L, PARENT_COMMENT_ID, TASK_ID);

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(userIdentityPort.findByEmail(EMAIL)).thenReturn(Optional.of(new UserIdentityDto(ACTOR_ID, EMAIL)));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, ACTOR_ID)).thenReturn(true);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(ProjectEntity.ProjectStatus.ACTIVE)));
        when(commentRepository.findByIdAndTaskId(PARENT_COMMENT_ID, TASK_ID)).thenReturn(Optional.of(parent));
        when(commentRepository.save(any(CommentEntity.class))).thenAnswer(invocation -> {
            CommentEntity comment = invocation.getArgument(0);
            comment.setId(COMMENT_ID);
            comment.setCreatedAt(Instant.parse("2026-05-13T00:00:00Z"));
            comment.setUpdatedAt(Instant.parse("2026-05-13T00:00:00Z"));
            return comment;
        });
        when(commentMentionRepository.findByCommentIdIn(anyCollection())).thenReturn(List.of());
        when(userProfilePort.findLiteByIds(anySet())).thenAnswer(invocation -> profiles(invocation.getArgument(0)));
        when(commentRepository.findParticipantUserIdsByTaskId(TASK_ID)).thenReturn(Set.of(ACTOR_ID, 2L));

        TaskCommentDto dto = service.createComment(TASK_ID,
                new CreateTaskCommentRequest("Nested reply", PARENT_COMMENT_ID, Set.of()), EMAIL);

        assertEquals(PARENT_COMMENT_ID, dto.parentCommentId());

        ArgumentCaptor<SystemNotificationCommandDto> captor = ArgumentCaptor
                .forClass(SystemNotificationCommandDto.class);
        verify(userNotificationPort).createNotification(captor.capture());

        SystemNotificationCommandDto command = captor.getValue();
        assertEquals(2L, command.targetUserId());
        assertEquals(NotificationTypeDto.REPLY, command.type());
        assertEquals("/tasks?taskId=" + TASK_ID + "&commentId=" + COMMENT_ID, command.linkAction());
    }

    @Test
    void createReplyDoesNotSendDuplicateReplyNotificationWhenParentAuthorIsMentioned() {
        TaskEntity task = task();
        CommentEntity parent = comment(2L, PARENT_COMMENT_ID, TASK_ID);

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(userIdentityPort.findByEmail(EMAIL)).thenReturn(Optional.of(new UserIdentityDto(ACTOR_ID, EMAIL)));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, ACTOR_ID)).thenReturn(true);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(ProjectEntity.ProjectStatus.ACTIVE)));
        when(projectMemberRepository.findMembers(PROJECT_ID)).thenReturn(members(1L, 2L));
        when(commentRepository.findByIdAndTaskId(PARENT_COMMENT_ID, TASK_ID)).thenReturn(Optional.of(parent));
        when(commentRepository.save(any(CommentEntity.class))).thenAnswer(invocation -> {
            CommentEntity comment = invocation.getArgument(0);
            comment.setId(COMMENT_ID);
            comment.setCreatedAt(Instant.parse("2026-05-13T00:00:00Z"));
            comment.setUpdatedAt(Instant.parse("2026-05-13T00:00:00Z"));
            return comment;
        });
        when(commentMentionRepository.findByCommentIdIn(anyCollection())).thenReturn(mentionsFor(COMMENT_ID, 2L));
        when(userProfilePort.findLiteByIds(anySet())).thenAnswer(invocation -> profiles(invocation.getArgument(0)));
        when(commentRepository.findParticipantUserIdsByTaskId(TASK_ID)).thenReturn(Set.of(1L, 2L));

        service.createComment(TASK_ID,
                new CreateTaskCommentRequest("Mention parent author", PARENT_COMMENT_ID, Set.of(2L)), EMAIL);

        ArgumentCaptor<SystemNotificationCommandDto> captor = ArgumentCaptor
                .forClass(SystemNotificationCommandDto.class);
        verify(userNotificationPort).createNotification(captor.capture());

        SystemNotificationCommandDto command = captor.getValue();
        assertEquals(2L, command.targetUserId());
        assertEquals(NotificationTypeDto.MENTION, command.type());
    }

    @Test
    void createReplyToCommentInDifferentTaskIsRejected() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task()));
        when(userIdentityPort.findByEmail(EMAIL)).thenReturn(Optional.of(new UserIdentityDto(ACTOR_ID, EMAIL)));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, ACTOR_ID)).thenReturn(true);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(ProjectEntity.ProjectStatus.ACTIVE)));
        when(commentRepository.findByIdAndTaskId(PARENT_COMMENT_ID, TASK_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createComment(TASK_ID,
                        new CreateTaskCommentRequest("Wrong parent", PARENT_COMMENT_ID, Set.of()), EMAIL));

        assertEquals(404, ex.getStatus());
    }

    @Test
    void createReplyToDeletedParentIsRejected() {
        CommentEntity parent = comment(2L, PARENT_COMMENT_ID, TASK_ID);
        parent.setDeletedAt(Instant.parse("2026-05-13T01:00:00Z"));

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task()));
        when(userIdentityPort.findByEmail(EMAIL)).thenReturn(Optional.of(new UserIdentityDto(ACTOR_ID, EMAIL)));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, ACTOR_ID)).thenReturn(true);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(ProjectEntity.ProjectStatus.ACTIVE)));
        when(commentRepository.findByIdAndTaskId(PARENT_COMMENT_ID, TASK_ID)).thenReturn(Optional.of(parent));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createComment(TASK_ID,
                        new CreateTaskCommentRequest("Deleted parent", PARENT_COMMENT_ID, Set.of()), EMAIL));

        assertEquals(409, ex.getStatus());
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

    @Test
    void nonAuthorCannotUpdateReply() {
        CommentEntity comment = comment(2L);
        comment.setParentCommentId(PARENT_COMMENT_ID);

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task()));
        when(commentRepository.findByIdAndTaskId(COMMENT_ID, TASK_ID)).thenReturn(Optional.of(comment));
        when(userIdentityPort.findByEmail(EMAIL)).thenReturn(Optional.of(new UserIdentityDto(ACTOR_ID, EMAIL)));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, ACTOR_ID)).thenReturn(true);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(ProjectEntity.ProjectStatus.ACTIVE)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateComment(TASK_ID, COMMENT_ID,
                        new UpdateTaskCommentRequest("Nope", Set.of()), EMAIL));

        assertEquals(403, ex.getStatus());
    }

    @Test
    void managerSoftDeletesParentAndKeepsDeletedShape() {
        CommentEntity comment = comment(2L);
        comment.setParentCommentId(PARENT_COMMENT_ID);

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task()));
        when(commentRepository.findByIdAndTaskId(COMMENT_ID, TASK_ID)).thenReturn(Optional.of(comment));
        when(userIdentityPort.findByEmail(EMAIL)).thenReturn(Optional.of(new UserIdentityDto(ACTOR_ID, EMAIL)));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, ACTOR_ID)).thenReturn(true);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(ProjectEntity.ProjectStatus.ACTIVE)));
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, ACTOR_ID)).thenReturn(Optional.of(
                ProjectMemberEntity.builder()
                        .projectId(PROJECT_ID)
                        .userId(ACTOR_ID)
                        .role(ProjectMemberEntity.MemberRole.MANAGER)
                        .build()));
        when(commentRepository.save(any(CommentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMentionRepository.findByCommentIdIn(anyCollection())).thenReturn(mentions(2L));
        when(userProfilePort.findLiteByIds(anySet())).thenAnswer(invocation -> profiles(invocation.getArgument(0)));

        TaskCommentDto dto = service.deleteComment(TASK_ID, COMMENT_ID, EMAIL);

        assertTrue(dto.deleted());
        assertNull(dto.content());
        assertTrue(dto.mentions().isEmpty());
        assertEquals(PARENT_COMMENT_ID, dto.parentCommentId());
        verify(realtimeService).publishDeleted(any());
    }

    @Test
    void searchCommentsReturnsContextAndHidesDeletedContent() {
        CommentEntity activeComment = comment(2L, COMMENT_ID, TASK_ID);
        CommentEntity deletedComment = comment(3L, 31L, TASK_ID);
        deletedComment.setDeletedAt(Instant.parse("2026-05-13T02:00:00Z"));

        when(userIdentityPort.findByEmail(EMAIL)).thenReturn(Optional.of(new UserIdentityDto(ACTOR_ID, EMAIL)));
        when(commentRepository.searchAccessibleComments(
                eq(ACTOR_ID),
                eq(false),
                eq("%"),
                isNull(),
                isNull(),
                isNull(),
                eq(false),
                anySet(),
                eq(false),
                any()))
                .thenReturn(new PageImpl<>(List.of(activeComment, deletedComment), PageRequest.of(0, 20), 2));
        when(commentMentionRepository.findByCommentIdIn(anyCollection())).thenReturn(mentionsFor(COMMENT_ID, 4L));
        when(taskRepository.findAllById(any())).thenReturn(List.of(task()));
        when(projectRepository.findAllById(any())).thenReturn(List.of(project(ProjectEntity.ProjectStatus.ACTIVE)));
        when(userProfilePort.findLiteByIds(anySet())).thenAnswer(invocation -> profiles(invocation.getArgument(0)));

        Page<CommentSearchResultDto> page = service.searchComments(
                null,
                null,
                null,
                null,
                false,
                EMAIL,
                PageRequest.of(0, 20));

        assertEquals(2, page.getTotalElements());
        CommentSearchResultDto active = page.getContent().get(0);
        assertEquals(PROJECT_ID, active.projectId());
        assertEquals("TaskPilot", active.projectName());
        assertEquals(TASK_ID, active.taskId());
        assertEquals("Implement comments", active.taskTitle());
        assertEquals("Original", active.content());
        assertFalse(active.deleted());
        assertEquals(1, active.mentions().size());

        CommentSearchResultDto deleted = page.getContent().get(1);
        assertTrue(deleted.deleted());
        assertNull(deleted.content());
        assertTrue(deleted.mentions().isEmpty());
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
        return comment(authorId, COMMENT_ID, TASK_ID);
    }

    private CommentEntity comment(Long authorId, Long commentId, Long taskId) {
        CommentEntity comment = CommentEntity.builder()
                .taskId(taskId)
                .userId(authorId)
                .content("Original")
                .build();
        comment.setId(commentId);
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
        return mentionsFor(COMMENT_ID, userIds);
    }

    private List<CommentMentionEntity> mentionsFor(Long commentId, Long... userIds) {
        return java.util.Arrays.stream(userIds)
                .map(userId -> new CommentMentionEntity(
                        new CommentMentionEntity.CommentMentionId(commentId, userId),
                        commentId,
                        userId,
                        Instant.parse("2026-05-13T00:00:00Z")))
                .toList();
    }

    private List<UserProfileLiteDto> profiles(Set<Long> userIds) {
        return userIds.stream()
                .map(userId -> new UserProfileLiteDto(userId, "User " + userId, null))
                .toList();
    }

    private boolean hasNotification(List<SystemNotificationCommandDto> commands, Long targetUserId,
            NotificationTypeDto type) {
        return commands.stream()
                .anyMatch(command -> command.targetUserId().equals(targetUserId)
                        && command.type() == type);
    }
}
