package com.taskpilot.projects.chat.service;

import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.realtime.RealtimeAfterCommitDispatcher;
import com.taskpilot.projects.chat.dto.ChatMessageResponse;
import com.taskpilot.projects.chat.dto.SendChatMessageRequest;
import com.taskpilot.projects.common.entity.ProjectChatMessageEntity;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.repository.ProjectChatMessageRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectChatService {

    private final ProjectChatMessageRepository chatMessageRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectSecurityService projectSecurityService;
    private final UserProfilePort userProfilePort;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public ChatMessageResponse sendMessage(Long projectId, CurrentActor actor, SendChatMessageRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Message content cannot be blank");
        }

        Long senderId = actor.userId();
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, senderId);

        String attachedFileName = null;
        if (request.fileId() != null) {
            attachedFileName = projectFileRepository.findByIdAndProjectId(request.fileId(), projectId)
                    .orElseThrow(() -> new BusinessException(404, "Attachment not found in this project"))
                    .getOriginalName();
        }

        ProjectChatMessageEntity entity = ProjectChatMessageEntity.builder()
                .projectId(projectId)
                .senderId(senderId)
                .content(request.content().trim())
                .messageType(request.messageType() != null ? request.messageType() : "TEXT")
                .fileId(request.fileId())
                .createdAt(Instant.now())
                .build();

        ProjectChatMessageEntity saved = chatMessageRepository.save(entity);

        UserProfileLiteDto profile = userProfilePort.findLiteById(senderId).orElse(null);
        String senderName = profile != null ? profile.fullName() : "User #" + senderId;
        String senderAvatarUrl = profile != null ? profile.avatarUrl() : null;

        ChatMessageResponse response = ChatMessageResponse.fromEntity(
                saved, senderName, actor.currentEmail(), senderAvatarUrl, attachedFileName);

        // Broadcast to WebSocket subscribers only after business commit
        String destination = "/topic/projects/" + projectId + "/chat";
        Long savedId = saved.getId();
        RealtimeAfterCommitDispatcher.runAfterCommit(() -> {
            try {
                messagingTemplate.convertAndSend(destination, response);
                log.debug("Broadcasted chat message {} to {}", savedId, destination);
            } catch (Exception e) {
                log.warn("Failed to broadcast chat message over WebSocket: {}", e.getMessage());
            }
        });

        return response;
    }

    @Transactional(readOnly = true)
    public Page<ChatMessageResponse> getChatHistory(Long projectId, Long userId, Pageable pageable) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        Page<ProjectChatMessageEntity> page = chatMessageRepository.findByProjectIdOrderByCreatedAtDesc(projectId, pageable);

        Set<Long> senderIds = page.getContent().stream()
                .map(ProjectChatMessageEntity::getSenderId)
                .collect(Collectors.toSet());

        Map<Long, UserProfileLiteDto> profileMap = resolveProfiles(senderIds);

        Set<Long> fileIds = page.getContent().stream()
                .map(ProjectChatMessageEntity::getFileId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> fileNames = resolveFileNames(projectId, fileIds);

        return page.map(msg -> {
            UserProfileLiteDto profile = profileMap.get(msg.getSenderId());
            String name = profile != null ? profile.fullName() : "User #" + msg.getSenderId();
            String avatar = profile != null ? profile.avatarUrl() : null;
            String fileName = msg.getFileId() != null ? fileNames.get(msg.getFileId()) : null;
            return ChatMessageResponse.fromEntity(msg, name, null, avatar, fileName);
        });
    }

    private Map<Long, UserProfileLiteDto> resolveProfiles(Set<Long> userIds) {
        if (userIds.isEmpty()) return Collections.emptyMap();
        List<UserProfileLiteDto> list = userProfilePort.findLiteByIds(userIds);
        Map<Long, UserProfileLiteDto> map = new HashMap<>();
        for (UserProfileLiteDto p : list) {
            map.put(p.id(), p);
        }
        return map;
    }

    private Map<Long, String> resolveFileNames(Long projectId, Set<Long> fileIds) {
        if (fileIds.isEmpty()) return Collections.emptyMap();
        List<ProjectFileEntity> files = projectFileRepository.findAllById(fileIds);
        Map<Long, String> map = new HashMap<>();
        for (ProjectFileEntity f : files) {
            if (Objects.equals(f.getProjectId(), projectId)) {
                map.put(f.getId(), f.getOriginalName());
            }
        }
        return map;
    }
}
