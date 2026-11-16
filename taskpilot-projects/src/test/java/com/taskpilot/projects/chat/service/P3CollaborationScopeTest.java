package com.taskpilot.projects.chat.service;

import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.chat.dto.SendChatMessageRequest;
import com.taskpilot.projects.common.repository.*;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.meetings.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class P3CollaborationScopeTest {
    @Test
    void foreignAttachmentCannotPersistOrBroadcastAMessage() {
        var messages = mock(ProjectChatMessageRepository.class);
        var files = mock(ProjectFileRepository.class);
        var security = mock(ProjectSecurityService.class);
        var profiles = mock(UserProfilePort.class);
        var broker = mock(SimpMessagingTemplate.class);
        var service = new ProjectChatService(messages, files, security, profiles, broker);
        assertThrows(BusinessException.class, () -> service.sendMessage(1L,
                new CurrentActor(7L,"a@example.com","USER",0L),
                new SendChatMessageRequest("attachment", "FILE", 99L)));
        verifyNoInteractions(messages, profiles, broker);
    }

    @Test
    void foreignMeetingCannotBeUsedForLeaveOrParticipantEnumeration() {
        var meetings = mock(ProjectMeetingRepository.class);
        var participants = mock(ProjectMeetingParticipantRepository.class);
        var service = new ProjectMeetingService(meetings, participants, mock(ProjectMemberRepository.class),
                mock(ProjectFileRepository.class), mock(ProjectSecurityService.class),
                mock(LiveKitTokenService.class), mock(UserProfilePort.class));
        assertThrows(BusinessException.class, () -> service.leaveMeeting(1L, 99L, 7L));
        assertThrows(BusinessException.class, () -> service.getParticipants(1L, 99L, 7L));
        verifyNoInteractions(participants);
    }
    @Test void legacyForeignAttachmentDoesNotExposeItsFilenameInHistory() {
        var messages=mock(ProjectChatMessageRepository.class);var files=mock(ProjectFileRepository.class);
        var profiles=mock(UserProfilePort.class);
        var service=new ProjectChatService(messages,files,mock(ProjectSecurityService.class),profiles,mock(SimpMessagingTemplate.class));
        var page=org.springframework.data.domain.PageRequest.of(0,20);
        var msg=com.taskpilot.projects.common.entity.ProjectChatMessageEntity.builder()
            .id(8L).projectId(1L).senderId(7L).content("legacy").fileId(99L).build();
        var file=com.taskpilot.projects.common.entity.ProjectFileEntity.builder()
            .id(99L).projectId(2L).originalName("foreign-secret.mp4").build();
        when(messages.findByProjectIdOrderByCreatedAtDesc(1L,page)).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(msg)));
        when(files.findAllById(java.util.Set.of(99L))).thenReturn(java.util.List.of(file));
        var history=service.getChatHistory(1L,7L,page);
        assertNull(history.getContent().getFirst().fileName());
    }
}
