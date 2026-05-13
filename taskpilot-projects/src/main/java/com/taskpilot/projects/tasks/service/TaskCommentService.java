package com.taskpilot.projects.tasks.service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.taskpilot.contracts.aiquery.dto.TaskCommentSummaryDto;
import com.taskpilot.contracts.aiquery.port.out.TaskCommentQueryPort;
import com.taskpilot.contracts.user.dto.NotificationTypeDto;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
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
import com.taskpilot.projects.tasks.dto.TaskCommentDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskCommentRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TaskCommentService implements TaskCommentQueryPort {

    private static final String TASK_LINK_PREFIX = "/tasks?taskId=";

    private final CommentRepository commentRepository;
    private final CommentMentionRepository commentMentionRepository;
    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserIdentityPort userIdentityPort;
    private final UserProfilePort userProfilePort;
    private final UserNotificationPort userNotificationPort;
    private final TaskCommentRealtimeService realtimeService;

    @Transactional(readOnly = true)
    public List<TaskCommentDto> getComments(Long taskId, String email) {
        TaskEntity task = findTask(taskId);
        Long currentUserId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), currentUserId);

        return mapToDtos(commentRepository.findByTaskIdOrderByCreatedAtAsc(taskId));
    }

    @Transactional
    public TaskCommentDto createComment(Long taskId, CreateTaskCommentRequest request, String email) {
        TaskEntity task = findTask(taskId);
        Long currentUserId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), currentUserId);
        validateProjectNotArchived(task.getProjectId());

        Set<Long> mentionedUserIds = validateMentionedUsers(task.getProjectId(), request.mentionedUserIds());
        String content = normalizeContent(request.content());

        CommentEntity comment = CommentEntity.builder()
                .taskId(taskId)
                .userId(currentUserId)
                .content(content)
                .build();
        commentRepository.save(comment);
        saveMentions(comment.getId(), mentionedUserIds);

        TaskCommentDto dto = mapToDto(comment);
        notifyCommentCreated(task, dto, mentionedUserIds, currentUserId);
        realtimeService.publishCreated(dto);
        return dto;
    }

    @Transactional
    public TaskCommentDto updateComment(Long taskId, Long commentId, UpdateTaskCommentRequest request, String email) {
        TaskEntity task = findTask(taskId);
        CommentEntity comment = findComment(taskId, commentId);
        Long currentUserId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), currentUserId);
        validateProjectNotArchived(task.getProjectId());

        if (!comment.getUserId().equals(currentUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Only the comment author can update it");
        }

        Set<Long> previousMentionIds = findMentionedUserIds(commentId);
        Set<Long> newMentionIds = validateMentionedUsers(task.getProjectId(), request.mentionedUserIds());

        comment.setContent(normalizeContent(request.content()));
        commentRepository.save(comment);
        replaceMentions(commentId, newMentionIds);

        TaskCommentDto dto = mapToDto(comment);
        notifyNewMentionsAfterEdit(task, dto, previousMentionIds, newMentionIds, currentUserId);
        realtimeService.publishUpdated(dto);
        return dto;
    }

    @Transactional
    public void deleteComment(Long taskId, Long commentId, String email) {
        TaskEntity task = findTask(taskId);
        CommentEntity comment = findComment(taskId, commentId);
        Long currentUserId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), currentUserId);
        validateProjectNotArchived(task.getProjectId());

        boolean isAuthor = comment.getUserId().equals(currentUserId);
        boolean isManager = projectMemberRepository.findByProjectIdAndUserId(task.getProjectId(), currentUserId)
                .map(member -> member.getRole() == ProjectMemberEntity.MemberRole.MANAGER)
                .orElse(false);
        if (!isAuthor && !isManager) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only the comment author or project manager can delete it");
        }

        commentRepository.delete(comment);
        realtimeService.publishDeleted(taskId, commentId);
    }

    @Transactional(readOnly = true)
    public SseEmitter streamComments(Long taskId, String email) {
        TaskEntity task = findTask(taskId);
        Long currentUserId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), currentUserId);
        return realtimeService.subscribe(taskId);
    }

    @Transactional(readOnly = true)
    public List<UserProfileLiteDto> getMentionCandidates(Long taskId, String keyword, String email) {
        TaskEntity task = findTask(taskId);
        Long currentUserId = getCurrentUserIdByEmail(email);
        validateUserIsMember(task.getProjectId(), currentUserId);

        Set<Long> memberIds = projectMemberRepository.findMembers(task.getProjectId()).stream()
                .map(ProjectMemberEntity::getUserId)
                .collect(Collectors.toSet());
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();

        return userProfilePort.findLiteByIds(memberIds).stream()
                .filter(profile -> normalizedKeyword.isBlank()
                        || profile.fullName().toLowerCase().contains(normalizedKeyword))
                .sorted(Comparator.comparing(UserProfileLiteDto::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskCommentSummaryDto> getTaskComments(Long taskId, Long requesterUserId) {
        TaskEntity task = findTask(taskId);
        validateUserIsMember(task.getProjectId(), requesterUserId);

        return mapToDtos(commentRepository.findByTaskIdOrderByCreatedAtAsc(taskId)).stream()
                .map(comment -> new TaskCommentSummaryDto(
                        comment.id(),
                        comment.taskId(),
                        comment.author().id(),
                        comment.author().fullName(),
                        comment.content(),
                        comment.mentions().stream().map(UserProfileLiteDto::id).toList(),
                        comment.createdAt(),
                        comment.updatedAt()))
                .toList();
    }

    private TaskEntity findTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Task not found"));
    }

    private CommentEntity findComment(Long taskId, Long commentId) {
        return commentRepository.findByIdAndTaskId(commentId, taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Comment not found"));
    }

    private Long getCurrentUserIdByEmail(String email) {
        return userIdentityPort.findByEmail(email)
                .map(identity -> identity.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }

    private void validateUserIsMember(Long projectId, Long userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "You are not a member of this project");
        }
    }

    private void validateProjectNotArchived(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectEntity.ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }
    }

    private String normalizeContent(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Comment content is required");
        }
        return normalized;
    }

    private Set<Long> validateMentionedUsers(Long projectId, Set<Long> mentionedUserIds) {
        Set<Long> normalizedIds = mentionedUserIds == null ? Set.of()
                : mentionedUserIds.stream()
                        .filter(id -> id != null)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedIds.isEmpty()) {
            return normalizedIds;
        }

        Set<Long> memberIds = projectMemberRepository.findMembers(projectId).stream()
                .map(ProjectMemberEntity::getUserId)
                .collect(Collectors.toSet());
        if (!memberIds.containsAll(normalizedIds)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "One or more mentioned users are not members of this project");
        }
        return normalizedIds;
    }

    private void saveMentions(Long commentId, Set<Long> mentionedUserIds) {
        if (mentionedUserIds.isEmpty()) {
            return;
        }
        List<CommentMentionEntity> mentions = mentionedUserIds.stream()
                .map(userId -> CommentMentionEntity.builder()
                        .id(new CommentMentionEntity.CommentMentionId(commentId, userId))
                        .commentId(commentId)
                        .userId(userId)
                        .build())
                .toList();
        commentMentionRepository.saveAll(mentions);
    }

    private void replaceMentions(Long commentId, Set<Long> mentionedUserIds) {
        commentMentionRepository.deleteByCommentId(commentId);
        saveMentions(commentId, mentionedUserIds);
    }

    private Set<Long> findMentionedUserIds(Long commentId) {
        return commentMentionRepository.findByCommentId(commentId).stream()
                .map(CommentMentionEntity::getUserId)
                .collect(Collectors.toSet());
    }

    private TaskCommentDto mapToDto(CommentEntity comment) {
        return mapToDtos(List.of(comment)).get(0);
    }

    private List<TaskCommentDto> mapToDtos(List<CommentEntity> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }

        List<Long> commentIds = comments.stream().map(CommentEntity::getId).toList();
        Map<Long, List<Long>> mentionIdsByComment = commentMentionRepository.findByCommentIdIn(commentIds)
                .stream()
                .collect(Collectors.groupingBy(
                        CommentMentionEntity::getCommentId,
                        Collectors.mapping(CommentMentionEntity::getUserId, Collectors.toList())));

        Set<Long> profileIds = comments.stream().map(CommentEntity::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        mentionIdsByComment.values().forEach(profileIds::addAll);

        Map<Long, UserProfileLiteDto> profilesById = userProfilePort.findLiteByIds(profileIds).stream()
                .collect(Collectors.toMap(UserProfileLiteDto::id, Function.identity(), (left, right) -> left));

        return comments.stream()
                .map(comment -> {
                    UserProfileLiteDto author = profilesById.getOrDefault(comment.getUserId(),
                            new UserProfileLiteDto(comment.getUserId(), "Unknown User"));
                    List<UserProfileLiteDto> mentions = mentionIdsByComment
                            .getOrDefault(comment.getId(), List.of())
                            .stream()
                            .map(profilesById::get)
                            .filter(profile -> profile != null)
                            .toList();
                    return new TaskCommentDto(
                            comment.getId(),
                            comment.getTaskId(),
                            author,
                            comment.getContent(),
                            mentions,
                            comment.getCreatedAt(),
                            comment.getUpdatedAt());
                })
                .toList();
    }

    private void notifyCommentCreated(TaskEntity task, TaskCommentDto comment, Set<Long> mentionedUserIds,
            Long actorUserId) {
        Set<Long> mentionRecipients = new LinkedHashSet<>(mentionedUserIds);
        mentionRecipients.remove(actorUserId);

        for (Long targetUserId : mentionRecipients) {
            userNotificationPort.createNotification(new SystemNotificationCommandDto(
                    targetUserId,
                    "You were mentioned in a task comment",
                    comment.author().fullName() + " mentioned you on task: " + task.getTitle(),
                    TASK_LINK_PREFIX + task.getId(),
                    NotificationTypeDto.MENTION));
        }

        Set<Long> commentRecipients = new LinkedHashSet<>();
        if (task.getAssigneeId() != null) {
            commentRecipients.add(task.getAssigneeId());
        }
        if (task.getReporterId() != null) {
            commentRecipients.add(task.getReporterId());
        }
        commentRecipients.addAll(commentRepository.findParticipantUserIdsByTaskId(task.getId()));
        commentRecipients.remove(actorUserId);
        commentRecipients.removeAll(mentionRecipients);

        for (Long targetUserId : commentRecipients) {
            userNotificationPort.createNotification(new SystemNotificationCommandDto(
                    targetUserId,
                    "New comment on task",
                    comment.author().fullName() + " commented on task: " + task.getTitle(),
                    TASK_LINK_PREFIX + task.getId(),
                    NotificationTypeDto.COMMENT));
        }
    }

    private void notifyNewMentionsAfterEdit(TaskEntity task, TaskCommentDto comment, Set<Long> previousMentionIds,
            Set<Long> newMentionIds, Long actorUserId) {
        Set<Long> newlyMentioned = new LinkedHashSet<>(newMentionIds);
        newlyMentioned.removeAll(previousMentionIds);
        newlyMentioned.remove(actorUserId);

        for (Long targetUserId : newlyMentioned) {
            userNotificationPort.createNotification(new SystemNotificationCommandDto(
                    targetUserId,
                    "You were mentioned in a task comment",
                    comment.author().fullName() + " mentioned you on task: " + task.getTitle(),
                    TASK_LINK_PREFIX + task.getId(),
                    NotificationTypeDto.MENTION));
        }
    }
}
