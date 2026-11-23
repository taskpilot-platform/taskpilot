package com.taskpilot.projects.meetings.service;

import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingParticipantEntity;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.ProjectMeetingParticipantRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.meetings.dto.CreateMeetingRequest;
import com.taskpilot.projects.meetings.dto.MeetingTokenResponse;
import com.taskpilot.projects.meetings.dto.ProjectMeetingResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectMeetingServiceTest {

    @Mock
    private ProjectMeetingRepository meetingRepository;

    @Mock
    private ProjectMeetingParticipantRepository participantRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @Mock
    private LiveKitTokenService liveKitTokenService;

    @Mock
    private UserProfilePort userProfilePort;

    @Mock
    private MeetingRoomGateway roomGateway;
    @Mock
    private MeetingRoomLifecycle roomLifecycle;
    @Mock
    private ClientMeetingRecordingService recordingService;

    @InjectMocks
    private ProjectMeetingService projectMeetingService;

    private final Long projectId = 10L;
    private final Long userId = 100L;
    private final CurrentActor actor = new CurrentActor(userId, "developer@taskpilot.io", "USER", 1L);

    @Test
    void createMeeting_success() {
        CreateMeetingRequest request = new CreateMeetingRequest("Sprint Planning", "Discuss Phase 4", true);

        when(meetingRepository.save(any(ProjectMeetingEntity.class))).thenAnswer(inv -> {
            ProjectMeetingEntity entity = inv.getArgument(0);
            entity.setId(1L);
            return entity;
        });

        UserProfileLiteDto profile = new UserProfileLiteDto(userId, "Alice Dev", "https://avatar.url");
        when(userProfilePort.findLiteById(userId)).thenReturn(Optional.of(profile));

        ProjectMeetingResponse response = projectMeetingService.createMeeting(projectId, userId, request);

        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("Sprint Planning", response.title());
        assertEquals(ProjectMeetingStatus.ACTIVE, response.status());
        assertTrue(response.isHost());
        assertTrue(response.recordingEnabled());
        verify(participantRepository, never()).save(any(ProjectMeetingParticipantEntity.class));
    }

    @Test
    void joinMeeting_success() {
        Long meetingId = 2L;
        ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                .id(meetingId)
                .projectId(projectId)
                .hostId(userId)
                .title("Standup")
                .roomName("tp-proj-10-room-abc")
                .status(ProjectMeetingStatus.ACTIVE)
                .startedAt(Instant.now().minusSeconds(60))
                .build();

        when(meetingRepository.findForUpdate(meetingId, projectId)).thenReturn(Optional.of(meeting));
        when(userProfilePort.findLiteById(userId))
                .thenReturn(Optional.of(new UserProfileLiteDto(userId, "Alice Dev", null)));
        when(roomGateway.ensureRoom(meeting.getRoomName())).thenReturn(MeetingRoomGateway.Outcome.SUCCESS);
        when(liveKitTokenService.getLivekitUrl()).thenReturn("wss://livekit.cloud");
        when(liveKitTokenService.createAccessToken(eq("tp-proj-10-room-abc"), eq("user_100"), anyString(), eq(true), anyMap()))
                .thenReturn("mock-signed-jwt-token");

        MeetingTokenResponse tokenResponse = projectMeetingService.joinMeeting(projectId, meetingId, actor);

        assertNotNull(tokenResponse);
        assertEquals("mock-signed-jwt-token", tokenResponse.token());
        assertEquals("wss://livekit.cloud", tokenResponse.livekitUrl());
        assertEquals("tp-proj-10-room-abc", tokenResponse.roomName());
        assertTrue(tokenResponse.isHost());
        verify(participantRepository, never()).save(any(ProjectMeetingParticipantEntity.class));
    }

    @Test
    void joinMeeting_endedMeeting_throwsBadRequest() {
        Long meetingId = 3L;
        ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                .id(meetingId)
                .projectId(projectId)
                .hostId(999L)
                .title("Retro")
                .roomName("room-retro")
                .status(ProjectMeetingStatus.ENDED)
                .build();

        when(meetingRepository.findForUpdate(meetingId, projectId)).thenReturn(Optional.of(meeting));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectMeetingService.joinMeeting(projectId, meetingId, actor));

        assertEquals(HttpStatus.CONFLICT.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("ended"));
    }

    @Test
    void endMeetingReflectsPendingProviderTermination() {
        Long meetingId = 4L;
        ProjectMeetingEntity pending = ProjectMeetingEntity.builder()
                .id(meetingId).projectId(projectId).hostId(userId).roomName("room-adhoc")
                .status(ProjectMeetingStatus.ENDING).build();
        when(meetingRepository.findByIdAndProjectId(meetingId, projectId)).thenReturn(Optional.of(pending));

        MeetingRecordingService cloudRecordings = mock(MeetingRecordingService.class);
        projectMeetingService.setMeetingRecordingService(cloudRecordings);
        var response = projectMeetingService.endMeeting(projectId, meetingId, userId);
        verify(cloudRecordings).stopRecordingIfActive(pending);

        verify(roomLifecycle).requestEnd(projectId, meetingId, userId);
        assertEquals(ProjectMeetingStatus.ENDING, response.status());
        assertNull(response.endedAt());
    }

    @Test
    void legacyLinkUsesTheSameRecordingScope() {
        Long meetingId = 5L;
        doThrow(new BusinessException(403, "Only the host can update the recording"))
                .when(recordingService).linkLegacy(projectId, meetingId, userId, 99L);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> projectMeetingService.updateRecordingFile(projectId, meetingId, userId, 99L)).getStatus());
        verifyNoInteractions(meetingRepository);
    }
}
