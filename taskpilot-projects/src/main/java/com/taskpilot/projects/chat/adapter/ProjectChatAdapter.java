package com.taskpilot.projects.chat.adapter;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.contracts.workspace.chat.ChatMessageSummaryDto;
import com.taskpilot.contracts.workspace.chat.ChatSearchCriteria;
import com.taskpilot.contracts.workspace.chat.ProjectChatPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.chat.dto.ChatMessageResponse;
import com.taskpilot.projects.common.entity.ProjectChatMessageEntity;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.repository.ProjectChatMessageRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectChatAdapter implements ProjectChatPort {

    private final ProjectChatMessageRepository chatMessageRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectSecurityService projectSecurityService;
    private final UserProfilePort userProfilePort;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageSummaryDto> getRecentMessages(Long projectId, Long beforeMessageId, int limit, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        int resolvedLimit = Math.min(Math.max(limit, 1), 100);
        List<ProjectChatMessageEntity> list;

        if (beforeMessageId != null && beforeMessageId > 0) {
            Page<ProjectChatMessageEntity> page = chatMessageRepository.findByProjectIdAndIdLessThanOrderByCreatedAtDesc(
                    projectId, beforeMessageId, PageRequest.of(0, resolvedLimit));
            list = page.getContent();
        } else {
            Page<ProjectChatMessageEntity> page = chatMessageRepository.findByProjectIdOrderByCreatedAtDesc(
                    projectId, PageRequest.of(0, resolvedLimit));
            list = page.getContent();
        }

        List<ProjectChatMessageEntity> chronological = new ArrayList<>(list);
        Collections.reverse(chronological);

        return mapToSummaryDtos(projectId, chronological);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageSummaryDto> searchMessages(Long projectId, ChatSearchCriteria criteria, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        int resolvedLimit = Math.min(Math.max(criteria != null ? criteria.limit() : 20, 1), 100);
        List<ProjectChatMessageEntity> list;

        if (criteria != null && criteria.keyword() != null && !criteria.keyword().isBlank()) {
            Page<ProjectChatMessageEntity> page = chatMessageRepository.findByProjectIdAndContentContainingIgnoreCaseOrderByCreatedAtDesc(
                    projectId, criteria.keyword().trim(), PageRequest.of(0, resolvedLimit));
            list = page.getContent();
        } else if (criteria != null && criteria.from() != null && criteria.to() != null) {
            list = chatMessageRepository.findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                    projectId, criteria.from(), criteria.to(), PageRequest.of(0, resolvedLimit));
        } else {
            Page<ProjectChatMessageEntity> page = chatMessageRepository.findByProjectIdOrderByCreatedAtDesc(
                    projectId, PageRequest.of(0, resolvedLimit));
            list = page.getContent();
        }

        if (criteria != null && criteria.senderName() != null && !criteria.senderName().isBlank()) {
            int fetchSize = Math.min(resolvedLimit * 3, 100);
            Page<ProjectChatMessageEntity> page = (criteria.keyword() != null && !criteria.keyword().isBlank())
                    ? chatMessageRepository.findByProjectIdAndContentContainingIgnoreCaseOrderByCreatedAtDesc(
                            projectId, criteria.keyword().trim(), PageRequest.of(0, fetchSize))
                    : chatMessageRepository.findByProjectIdOrderByCreatedAtDesc(projectId, PageRequest.of(0, fetchSize));
            list = page.getContent();
            String target = criteria.senderName().toLowerCase();
            return mapToSummaryDtos(projectId, list).stream()
                    .filter(m -> m.senderName() != null && m.senderName().toLowerCase().contains(target))
                    .limit(resolvedLimit)
                    .toList();
        }

        return mapToSummaryDtos(projectId, list);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageSummaryDto> getMessagesBetween(Long projectId, Instant from, Instant to, int limit, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        int resolvedLimit = Math.min(Math.max(limit, 1), 100);
        List<ProjectChatMessageEntity> list = chatMessageRepository.findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                projectId, from, to, PageRequest.of(0, resolvedLimit));

        return mapToSummaryDtos(projectId, list);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canAnnounce(Long projectId, Long requesterUserId) {
        if (requesterUserId == null) return false;
        try {
            projectSecurityService.requireActiveProject(projectId);
            Optional<ProjectMemberEntity> memberOpt = projectMemberRepository.findByProjectIdAndUserId(projectId, requesterUserId);
            return memberOpt.isPresent() && memberOpt.get().getRole() == MemberRole.MANAGER;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    @Transactional
    public ChatMessageSummaryDto postAnnouncement(Long projectId, String content, Long requesterUserId) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Announcement content cannot be blank");
        }
        if (!canAnnounce(projectId, requesterUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Only project managers can post announcements");
        }

        ProjectChatMessageEntity entity = ProjectChatMessageEntity.builder()
                .projectId(projectId)
                .senderId(requesterUserId)
                .content(content.trim())
                .messageType("ANNOUNCEMENT")
                .createdAt(Instant.now())
                .build();

        ProjectChatMessageEntity saved = chatMessageRepository.save(entity);

        UserProfileLiteDto profile = userProfilePort.findLiteById(requesterUserId).orElse(null);
        String senderName = profile != null ? profile.fullName() : "User #" + requesterUserId;
        String senderAvatarUrl = profile != null ? profile.avatarUrl() : null;

        ChatMessageResponse response = ChatMessageResponse.fromEntity(
                saved, senderName, null, senderAvatarUrl, null);

        try {
            messagingTemplate.convertAndSend("/topic/projects/" + projectId + "/chat", response);
            log.info("Broadcasted announcement {} to project {}", saved.getId(), projectId);
        } catch (Exception e) {
            log.warn("Failed to broadcast announcement over WebSocket: {}", e.getMessage());
        }

        return new ChatMessageSummaryDto(
                saved.getId(),
                saved.getSenderId(),
                senderName,
                saved.getContent(),
                saved.getMessageType(),
                null,
                null,
                saved.getCreatedAt()
        );
    }

    private void validateAccess(Long projectId, Long userId) {
        projectSecurityService.requireProject(projectId);
        if (userId != null) {
            projectSecurityService.validateMember(projectId, userId);
        }
    }

    private List<ChatMessageSummaryDto> mapToSummaryDtos(Long projectId, List<ProjectChatMessageEntity> entities) {
        if (entities.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> senderIds = entities.stream()
                .map(ProjectChatMessageEntity::getSenderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> fileIds = entities.stream()
                .map(ProjectChatMessageEntity::getFileId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, UserProfileLiteDto> profileMap = resolveProfiles(senderIds);
        Map<Long, String> fileNamesMap = resolveFileNames(fileIds, projectId);

        return entities.stream().map(msg -> {
            UserProfileLiteDto p = profileMap.get(msg.getSenderId());
            String senderName = p != null ? p.fullName() : "User #" + msg.getSenderId();
            String fileName = msg.getFileId() != null ? fileNamesMap.get(msg.getFileId()) : null;

            return new ChatMessageSummaryDto(
                    msg.getId(),
                    msg.getSenderId(),
                    senderName,
                    msg.getContent(),
                    msg.getMessageType(),
                    msg.getFileId(),
                    fileName,
                    msg.getCreatedAt()
            );
        }).toList();
    }

    private Map<Long, UserProfileLiteDto> resolveProfiles(Set<Long> userIds) {
        if (userIds.isEmpty()) return Collections.emptyMap();
        List<UserProfileLiteDto> profiles = userProfilePort.findLiteByIds(userIds);
        Map<Long, UserProfileLiteDto> map = new HashMap<>();
        for (UserProfileLiteDto p : profiles) {
            map.put(p.id(), p);
        }
        return map;
    }

    private Map<Long, String> resolveFileNames(Set<Long> fileIds, Long projectId) {
        if (fileIds.isEmpty()) return Collections.emptyMap();
        Map<Long, String> map = new HashMap<>();
        for (Long fId : fileIds) {
            projectFileRepository.findByIdAndProjectId(fId, projectId)
                    .ifPresent(f -> map.put(fId, f.getOriginalName()));
        }
        return map;
    }
}
