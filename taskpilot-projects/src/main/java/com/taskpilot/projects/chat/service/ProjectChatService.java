package com.taskpilot.projects.chat.service;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
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
    private final UserIdentityPort userIdentityPort;
    private final UserProfilePort userProfilePort;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public ChatMessageResponse sendMessage(Long projectId, String senderEmail, SendChatMessageRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Message content cannot be blank");
        }

        Long senderId = getUserId(senderEmail);
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, senderId);

        String attachedFileName = null;
        if (request.fileId() != null) {
            Optional<ProjectFileEntity> fileOpt = projectFileRepository.findByIdAndProjectId(request.fileId(), projectId);
            if (fileOpt.isPresent()) {
                attachedFileName = fileOpt.get().getOriginalName();
            }
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
                saved, senderName, senderEmail, senderAvatarUrl, attachedFileName);

        // Broadcast to WebSocket subscribers for this project
        try {
            String destination = "/topic/projects/" + projectId + "/chat";
            messagingTemplate.convertAndSend(destination, response);
            log.debug("Broadcasted chat message {} to {}", saved.getId(), destination);
        } catch (Exception e) {
            log.warn("Failed to broadcast chat message over WebSocket: {}", e.getMessage());
        }

        return response;
    }

    @Transactional(readOnly = true)
    public Page<ChatMessageResponse> getChatHistory(Long projectId, String userEmail, Pageable pageable) {
        Long userId = getUserId(userEmail);
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

        Map<Long, String> fileNames = resolveFileNames(fileIds);

        return page.map(msg -> {
            UserProfileLiteDto profile = profileMap.get(msg.getSenderId());
            String name = profile != null ? profile.fullName() : "User #" + msg.getSenderId();
            String avatar = profile != null ? profile.avatarUrl() : null;
            String fileName = msg.getFileId() != null ? fileNames.get(msg.getFileId()) : null;
            return ChatMessageResponse.fromEntity(msg, name, null, avatar, fileName);
        });
    }

    private Long getUserId(String email) {
        return userIdentityPort.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"))
                .id();
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

    private Map<Long, String> resolveFileNames(Set<Long> fileIds) {
        if (fileIds.isEmpty()) return Collections.emptyMap();
        List<ProjectFileEntity> files = projectFileRepository.findAllById(fileIds);
        Map<Long, String> map = new HashMap<>();
        for (ProjectFileEntity f : files) {
            map.put(f.getId(), f.getOriginalName());
        }
        return map;
    }
}
