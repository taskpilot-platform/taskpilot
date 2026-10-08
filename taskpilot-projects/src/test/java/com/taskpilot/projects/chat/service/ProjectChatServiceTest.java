package com.taskpilot.projects.chat.service;

import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.chat.dto.ChatMessageResponse;
import com.taskpilot.projects.chat.dto.SendChatMessageRequest;
import com.taskpilot.projects.common.entity.ProjectChatMessageEntity;
import com.taskpilot.projects.common.repository.ProjectChatMessageRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectChatServiceTest {

    @Mock
    private ProjectChatMessageRepository chatMessageRepository;

    @Mock
    private ProjectFileRepository projectFileRepository;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @Mock
    private UserIdentityPort userIdentityPort;

    @Mock
    private UserProfilePort userProfilePort;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ProjectChatService projectChatService;

    private final Long projectId = 5L;
    private final Long userId = 42L;
    private final String email = "alice@example.com";

    @BeforeEach
    void setUp() {
        lenient().when(userIdentityPort.findByEmail(email)).thenReturn(Optional.of(new UserIdentityDto(userId, email)));
    }

    @Test
    void sendMessage_success() {
        SendChatMessageRequest request = new SendChatMessageRequest("Hello team!", "TEXT", null);
        ProjectChatMessageEntity savedEntity = ProjectChatMessageEntity.builder()
                .id(101L)
                .projectId(projectId)
                .senderId(userId)
                .content("Hello team!")
                .messageType("TEXT")
                .build();

        when(chatMessageRepository.save(any())).thenReturn(savedEntity);
        when(userProfilePort.findLiteById(userId)).thenReturn(Optional.of(new UserProfileLiteDto(userId, "Alice", "/avatar.png")));

        ChatMessageResponse response = projectChatService.sendMessage(projectId, email, request);

        assertNotNull(response);
        assertEquals(101L, response.id());
        assertEquals("Hello team!", response.content());
        assertEquals("Alice", response.senderName());

        verify(projectSecurityService).requireActiveProject(projectId);
        verify(projectSecurityService).validateMember(projectId, userId);
        verify(messagingTemplate).convertAndSend(eq("/topic/projects/5/chat"), eq(response));
    }

    @Test
    void sendMessage_blankContent_throwsBadRequest() {
        SendChatMessageRequest request = new SendChatMessageRequest("   ", "TEXT", null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectChatService.sendMessage(projectId, email, request));
        assertEquals(400, ex.getStatus());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void getChatHistory_success() {
        ProjectChatMessageEntity entity = ProjectChatMessageEntity.builder()
                .id(1L)
                .projectId(projectId)
                .senderId(userId)
                .content("First message")
                .build();
        PageRequest pageRequest = PageRequest.of(0, 50);
        when(chatMessageRepository.findByProjectIdOrderByCreatedAtDesc(projectId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(entity)));
        when(userProfilePort.findLiteByIds(anySet()))
                .thenReturn(List.of(new UserProfileLiteDto(userId, "Alice", "/avatar.png")));

        Page<ChatMessageResponse> history = projectChatService.getChatHistory(projectId, email, pageRequest);

        assertEquals(1, history.getTotalElements());
        assertEquals("First message", history.getContent().get(0).content());
        assertEquals("Alice", history.getContent().get(0).senderName());
    }
}
