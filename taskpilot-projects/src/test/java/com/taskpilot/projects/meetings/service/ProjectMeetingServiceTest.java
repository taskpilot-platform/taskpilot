package com.taskpilot.projects.meetings.service;

import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingParticipantEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectMeetingRole;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.ProjectMeetingParticipantRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.meetings.dto.CreateMeetingRequest;
import com.taskpilot.projects.meetings.dto.MeetingParticipantResponse;
import com.taskpilot.projects.meetings.dto.MeetingTokenResponse;
import com.taskpilot.projects.meetings.dto.ProjectMeetingResponse;
import org.junit.jupiter.api.BeforeEach;
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
    private UserIdentityPort userIdentityPort;

    @Mock
    private UserProfilePort userProfilePort;

    @InjectMocks
    private ProjectMeetingService projectMeetingService;

    private final Long projectId = 10L;
    private final Long userId = 100L;
    private final String email = "developer@taskpilot.io";

    @BeforeEach
    void setUp() {
        lenient().when(userIdentityPort.findByEmail(email))
                .thenReturn(Optional.of(new UserIdentityDto(userId, email)));
    }

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
        when(participantRepository.countByMeetingIdAndLeftAtIsNull(1L)).thenReturn(1L);
        when(participantRepository.countByMeetingId(1L)).thenReturn(1L);

        ProjectMeetingResponse response = projectMeetingService.createMeeting(projectId, email, request);

        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("Sprint Planning", response.title());
        assertEquals(ProjectMeetingStatus.ACTIVE, response.status());
        assertTrue(response.isHost());
        assertTrue(response.recordingEnabled());
        verify(participantRepository).save(any(ProjectMeetingParticipantEntity.class));
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

        when(meetingRepository.findByIdAndProjectId(meetingId, projectId)).thenReturn(Optional.of(meeting));
        when(participantRepository.findFirstByMeetingIdAndUserIdAndLeftAtIsNullOrderByJoinedAtDesc(meetingId, userId))
                .thenReturn(Optional.empty());
        when(userProfilePort.findLiteById(userId))
                .thenReturn(Optional.of(new UserProfileLiteDto(userId, "Alice Dev", null)));
        when(liveKitTokenService.getLivekitUrl()).thenReturn("wss://livekit.cloud");
        when(liveKitTokenService.createAccessToken(eq("tp-proj-10-room-abc"), eq("user_100"), anyString(), eq(true), anyMap()))
                .thenReturn("mock-signed-jwt-token");

        MeetingTokenResponse tokenResponse = projectMeetingService.joinMeeting(projectId, meetingId, email);

        assertNotNull(tokenResponse);
        assertEquals("mock-signed-jwt-token", tokenResponse.token());
        assertEquals("wss://livekit.cloud", tokenResponse.livekitUrl());
        assertEquals("tp-proj-10-room-abc", tokenResponse.roomName());
        assertTrue(tokenResponse.isHost());
        verify(participantRepository).save(any(ProjectMeetingParticipantEntity.class));
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

        when(meetingRepository.findByIdAndProjectId(meetingId, projectId)).thenReturn(Optional.of(meeting));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectMeetingService.joinMeeting(projectId, meetingId, email));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatus());
        assertTrue(ex.getMessage().contains("ended"));
    }

    @Test
    void endMeeting_byHost_success() {
        Long meetingId = 4L;
        ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                .id(meetingId)
                .projectId(projectId)
                .hostId(userId)
                .title("Ad-hoc")
                .roomName("room-adhoc")
                .status(ProjectMeetingStatus.ACTIVE)
                .startedAt(Instant.now().minusSeconds(300))
                .build();

        when(meetingRepository.findByIdAndProjectId(meetingId, projectId)).thenReturn(Optional.of(meeting));
        when(participantRepository.findByMeetingIdAndLeftAtIsNullOrderByJoinedAtAsc(meetingId))
                .thenReturn(List.of(ProjectMeetingParticipantEntity.builder()
                        .meetingId(meetingId)
                        .userId(userId)
                        .joinedAt(Instant.now().minusSeconds(300))
                        .build()));

        ProjectMeetingResponse response = projectMeetingService.endMeeting(projectId, meetingId, email);

        assertNotNull(response);
        assertEquals(ProjectMeetingStatus.ENDED, meeting.getStatus());
        assertNotNull(meeting.getEndedAt());
        verify(meetingRepository).save(meeting);
        verify(participantRepository).saveAll(anyList());
    }

    @Test
    void endMeeting_byNonHostNonManager_throwsForbidden() {
        Long meetingId = 5L;
        ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                .id(meetingId)
                .projectId(projectId)
                .hostId(999L) // different host
                .title("Private")
                .roomName("room-priv")
                .status(ProjectMeetingStatus.ACTIVE)
                .build();

        when(meetingRepository.findByIdAndProjectId(meetingId, projectId)).thenReturn(Optional.of(meeting));
        when(projectMemberRepository.findByProjectIdAndUserId(projectId, userId))
                .thenReturn(Optional.of(ProjectMemberEntity.builder()
                        .role(MemberRole.MEMBER) // regular member
                        .build()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectMeetingService.endMeeting(projectId, meetingId, email));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatus());
        verify(meetingRepository, never()).save(any());
    }
}
