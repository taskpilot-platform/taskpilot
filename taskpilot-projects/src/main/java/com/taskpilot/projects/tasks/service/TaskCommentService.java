package com.taskpilot.projects.tasks.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import com.taskpilot.projects.tasks.dto.CommentSearchResultDto;
import com.taskpilot.projects.tasks.dto.CreateTaskCommentRequest;
import com.taskpilot.projects.tasks.dto.TaskCommentDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskCommentRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TaskCommentService implements TaskCommentQueryPort {

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
        CommentEntity parentComment = validateParentComment(taskId, request.parentCommentId());

        CommentEntity comment = CommentEntity.builder()
                .taskId(taskId)
                .parentCommentId(parentComment != null ? parentComment.getId() : null)
                .userId(currentUserId)
                .content(content)
                .build();
        commentRepository.save(comment);
        saveMentions(comment.getId(), mentionedUserIds);

        TaskCommentDto dto = mapToDto(comment);
        notifyCommentCreated(task, parentComment, dto, mentionedUserIds, currentUserId);
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
        if (isDeleted(comment)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Deleted comments cannot be updated");
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
    public TaskCommentDto deleteComment(Long taskId, Long commentId, String email) {
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

        if (isDeleted(comment)) {
            return mapToDto(comment);
        }

        comment.setDeletedAt(Instant.now());
        comment.setDeletedBy(currentUserId);
        commentRepository.save(comment);

        TaskCommentDto dto = mapToDto(comment);
        realtimeService.publishDeleted(dto);
        return dto;
    }

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

    @Transactional(readOnly = true)
    public Page<CommentSearchResultDto> searchComments(
            String keyword,
            Long projectId,
            Long taskId,
            Long authorId,
            boolean mentionedMe,
            String email,
            Pageable pageable) {
        Long currentUserId = getCurrentUserIdByEmail(email);
        String normalizedKeyword = normalizeOptionalKeyword(keyword);
        boolean hasKeyword = normalizedKeyword != null;
        String keywordPattern = hasKeyword
                ? "%" + normalizedKeyword.toLowerCase(Locale.ROOT) + "%"
                : "%";
        Set<Long> keywordAuthorIds = findKeywordAuthorIds(normalizedKeyword);
        boolean hasKeywordAuthorMatches = !keywordAuthorIds.isEmpty();
        if (!hasKeywordAuthorMatches) {
            keywordAuthorIds = Set.of(-1L);
        }

        Pageable safePageable = buildCommentSearchPageable(pageable);
        Page<CommentEntity> commentPage = commentRepository.searchAccessibleComments(
                currentUserId,
                hasKeyword,
                keywordPattern,
                projectId,
                taskId,
                authorId,
                mentionedMe,
                keywordAuthorIds,
                hasKeywordAuthorMatches,
                safePageable);

        List<CommentSearchResultDto> results = mapToSearchResultDtos(commentPage.getContent());
        return new PageImpl<>(results, safePageable, commentPage.getTotalElements());
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
                        comment.parentCommentId(),
                        comment.author().id(),
                        comment.author().fullName(),
                        comment.content(),
                        comment.mentions().stream().map(UserProfileLiteDto::id).toList(),
                        comment.deleted(),
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

    private CommentEntity validateParentComment(Long taskId, Long parentCommentId) {
        if (parentCommentId == null) {
            return null;
        }

        CommentEntity parentComment = findComment(taskId, parentCommentId);
        if (isDeleted(parentComment)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Cannot reply to a deleted comment");
        }
        return parentComment;
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

    private List<CommentSearchResultDto> mapToSearchResultDtos(List<CommentEntity> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }

        List<Long> commentIds = comments.stream().map(CommentEntity::getId).toList();
        Map<Long, List<Long>> mentionIdsByComment = commentMentionRepository.findByCommentIdIn(commentIds)
                .stream()
                .collect(Collectors.groupingBy(
                        CommentMentionEntity::getCommentId,
                        Collectors.mapping(CommentMentionEntity::getUserId, Collectors.toList())));

        Map<Long, TaskEntity> tasksById = taskRepository.findAllById(
                comments.stream().map(CommentEntity::getTaskId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(TaskEntity::getId, Function.identity()));

        Map<Long, ProjectEntity> projectsById = projectRepository.findAllById(
                tasksById.values().stream().map(TaskEntity::getProjectId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ProjectEntity::getId, Function.identity()));

        Set<Long> profileIds = comments.stream().map(CommentEntity::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        mentionIdsByComment.values().forEach(profileIds::addAll);

        Map<Long, UserProfileLiteDto> profilesById = loadProfilesById(profileIds);

        return comments.stream()
                .map(comment -> {
                    TaskEntity task = tasksById.get(comment.getTaskId());
                    ProjectEntity project = task == null ? null : projectsById.get(task.getProjectId());
                    boolean deleted = isDeleted(comment);
                    UserProfileLiteDto author = profilesById.getOrDefault(comment.getUserId(),
                            new UserProfileLiteDto(comment.getUserId(), "Unknown User", null));
                    List<UserProfileLiteDto> mentions = deleted ? List.of()
                            : mentionIdsByComment.getOrDefault(comment.getId(), List.of())
                                    .stream()
                                    .map(profilesById::get)
                                    .filter(profile -> profile != null)
                                    .toList();

                    return new CommentSearchResultDto(
                            comment.getId(),
                            project != null ? project.getId() : null,
                            project != null ? project.getName() : "Unknown Project",
                            comment.getTaskId(),
                            task != null ? task.getTitle() : "Unknown Task",
                            comment.getParentCommentId(),
                            author,
                            deleted ? null : comment.getContent(),
                            mentions,
                            deleted,
                            comment.getDeletedAt(),
                            comment.getCreatedAt(),
                            comment.getUpdatedAt());
                })
                .toList();
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

        Map<Long, UserProfileLiteDto> profilesById = loadProfilesById(profileIds);

        return comments.stream()
                .map(comment -> {
                    UserProfileLiteDto author = profilesById.getOrDefault(comment.getUserId(),
                            new UserProfileLiteDto(comment.getUserId(), "Unknown User", null));
                    List<UserProfileLiteDto> mentions = mentionIdsByComment
                            .getOrDefault(comment.getId(), List.of())
                            .stream()
                            .map(profilesById::get)
                            .filter(profile -> profile != null)
                            .toList();
                    return new TaskCommentDto(
                            comment.getId(),
                            comment.getTaskId(),
                            comment.getParentCommentId(),
                            author,
                            isDeleted(comment) ? null : comment.getContent(),
                            isDeleted(comment) ? List.of() : mentions,
                            isDeleted(comment),
                            comment.getDeletedAt(),
                            comment.getCreatedAt(),
                            comment.getUpdatedAt());
                })
                .toList();
    }

    private void notifyCommentCreated(TaskEntity task, CommentEntity parentComment, TaskCommentDto comment,
            Set<Long> mentionedUserIds, Long actorUserId) {
        Set<Long> mentionRecipients = new LinkedHashSet<>(mentionedUserIds);
        mentionRecipients.remove(actorUserId);

        String linkAction = buildCommentLink(task.getId(), comment.id());

        for (Long targetUserId : mentionRecipients) {
            userNotificationPort.createNotification(new SystemNotificationCommandDto(
                    targetUserId,
                    "You were mentioned in a task comment",
                    comment.author().fullName() + " mentioned you on task: " + task.getTitle(),
                    linkAction,
                    NotificationTypeDto.MENTION));
        }

        Set<Long> replyRecipients = new LinkedHashSet<>();
        if (parentComment != null && parentComment.getUserId() != null) {
            replyRecipients.add(parentComment.getUserId());
        }
        replyRecipients.remove(actorUserId);
        replyRecipients.removeAll(mentionRecipients);

        for (Long targetUserId : replyRecipients) {
            userNotificationPort.createNotification(new SystemNotificationCommandDto(
                    targetUserId,
                    "New reply to your task comment",
                    comment.author().fullName() + " replied on task: " + task.getTitle(),
                    linkAction,
                    NotificationTypeDto.REPLY));
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
        commentRecipients.removeAll(replyRecipients);

        for (Long targetUserId : commentRecipients) {
            userNotificationPort.createNotification(new SystemNotificationCommandDto(
                    targetUserId,
                    "New comment on task",
                    comment.author().fullName() + " commented on task: " + task.getTitle(),
                    linkAction,
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
                    buildCommentLink(task.getId(), comment.id()),
                    NotificationTypeDto.MENTION));
        }
    }

    private String buildCommentLink(Long taskId, Long commentId) {
        return "/tasks?taskId=" + taskId + "&commentId=" + commentId;
    }

    private boolean isDeleted(CommentEntity comment) {
        return comment.getDeletedAt() != null;
    }

    private Map<Long, UserProfileLiteDto> loadProfilesById(Collection<Long> profileIds) {
        if (profileIds == null || profileIds.isEmpty()) {
            return Map.of();
        }

        return userProfilePort.findLiteByIds(new HashSet<>(profileIds)).stream()
                .collect(Collectors.toMap(UserProfileLiteDto::id, Function.identity(), (left, right) -> left));
    }

    private String normalizeOptionalKeyword(String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        return normalizedKeyword.isBlank() ? null : normalizedKeyword;
    }

    private Set<Long> findKeywordAuthorIds(String keyword) {
        if (keyword == null) {
            return Set.of();
        }

        return userProfilePort.searchLite(keyword, 1000).stream()
                .map(UserProfileLiteDto::id)
                .collect(Collectors.toSet());
    }

    private Pageable buildCommentSearchPageable(Pageable pageable) {
        int page = Math.max(0, pageable.getPageNumber());
        int size = Math.max(1, Math.min(pageable.getPageSize(), 100));
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
