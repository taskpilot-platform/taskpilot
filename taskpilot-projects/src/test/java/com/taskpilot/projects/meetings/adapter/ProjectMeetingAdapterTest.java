package com.taskpilot.projects.meetings.adapter;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.contracts.workspace.meeting.MeetingDetailDto;
import com.taskpilot.contracts.workspace.meeting.MeetingListItemDto;
import com.taskpilot.contracts.workspace.meeting.MeetingSourceKind;
import com.taskpilot.contracts.workspace.meeting.MeetingSourceTextDto;
import com.taskpilot.contracts.workspace.meeting.ScheduleMeetingCommand;
import com.taskpilot.projects.common.entity.ProjectChatMessageEntity;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingParticipantEntity;
import com.taskpilot.projects.common.enums.ProjectMeetingRole;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.ProjectChatMessageRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingParticipantRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectMeetingAdapterTest {

    private ProjectMeetingRepository meetingRepository;
    private ProjectMeetingParticipantRepository participantRepository;
    private ProjectFileRepository projectFileRepository;
    private ProjectChatMessageRepository chatMessageRepository;
    private ProjectSecurityService projectSecurityService;
    private UserProfilePort userProfilePort;
    private ProjectMeetingAdapter adapter;

    @BeforeEach
    void setUp() {
        meetingRepository = Mockito.mock(ProjectMeetingRepository.class);
        participantRepository = Mockito.mock(ProjectMeetingParticipantRepository.class);
        projectFileRepository = Mockito.mock(ProjectFileRepository.class);
        chatMessageRepository = Mockito.mock(ProjectChatMessageRepository.class);
        projectSecurityService = Mockito.mock(ProjectSecurityService.class);
        userProfilePort = Mockito.mock(UserProfilePort.class);

        adapter = new ProjectMeetingAdapter(
                meetingRepository,
                participantRepository,
                projectFileRepository,
                chatMessageRepository,
                projectSecurityService,
                userProfilePort);
    }

    @Test
    void testListMeetings_AllStatus() {
        ProjectMeetingEntity m1 = ProjectMeetingEntity.builder()
                .id(1L)
                .projectId(10L)
                .title("Standup")
                .status(ProjectMeetingStatus.ACTIVE)
                .hostId(100L)
                .createdAt(Instant.now())
                .build();

        when(meetingRepository.findByProjectIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(m1));
        when(userProfilePort.findLiteByIds(Set.of(100L))).thenReturn(List.of(new UserProfileLiteDto(100L, "Alice", null)));

        List<MeetingListItemDto> result = adapter.listMeetings(10L, null, 20, 100L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Standup");
        assertThat(result.get(0).hostName()).isEqualTo("Alice");
    }

    @Test
    void testGetMeetingDetails_WithRecording() {
        ProjectMeetingEntity m = ProjectMeetingEntity.builder()
                .id(1L)
                .projectId(10L)
                .title("Review")
                .status(ProjectMeetingStatus.ENDED)
                .hostId(100L)
                .recordingFileId(99L)
                .aiSummary("{\"overview\":\"Good meeting\"}")
                .build();

        when(meetingRepository.findByIdAndProjectId(1L, 10L)).thenReturn(Optional.of(m));
        when(userProfilePort.findLiteById(100L)).thenReturn(Optional.of(new UserProfileLiteDto(100L, "Alice", null)));

        ProjectFileEntity file = ProjectFileEntity.builder()
                .id(99L)
                .projectId(10L)
                .originalName("review.mp4")
                .build();
        when(projectFileRepository.findByIdAndProjectId(99L, 10L)).thenReturn(Optional.of(file));

        MeetingDetailDto detail = adapter.getMeetingDetails(10L, 1L, 100L);
        assertThat(detail).isNotNull();
        assertThat(detail.recordingFileName()).isEqualTo("review.mp4");
        assertThat(detail.recordingAppRoute()).isEqualTo("/projects/10/files/99");
        assertThat(detail.aiSummaryJson()).contains("Good meeting");
    }

    @Test
    void testScheduleMeeting_Success() {
        ScheduleMeetingCommand command = new ScheduleMeetingCommand(
                "Sprint Kickoff", "Kickoff description",
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), true);

        ProjectMeetingEntity savedEntity = ProjectMeetingEntity.builder()
                .id(5L)
                .projectId(10L)
                .title("Sprint Kickoff")
                .description("Kickoff description")
                .roomName("tp-proj-10-m-12345678")
                .status(ProjectMeetingStatus.SCHEDULED)
                .hostId(100L)
                .recordingEnabled(true)
                .scheduledStartTime(command.start())
                .scheduledEndTime(command.end())
                .createdAt(Instant.now())
                .build();

        when(meetingRepository.save(any(ProjectMeetingEntity.class))).thenReturn(savedEntity);
        when(meetingRepository.findByIdAndProjectId(5L, 10L)).thenReturn(Optional.of(savedEntity));
        when(userProfilePort.findLiteById(100L)).thenReturn(Optional.of(new UserProfileLiteDto(100L, "Alice", null)));

        MeetingDetailDto result = adapter.scheduleMeeting(10L, command, 100L);
        assertThat(result).isNotNull();
        assertThat(result.meeting().title()).isEqualTo("Sprint Kickoff");
        assertThat(result.meeting().status()).isEqualTo("SCHEDULED");

        verify(participantRepository).save(any(ProjectMeetingParticipantEntity.class));
    }

    @Test
    void testGetMeetingSourceText_TranscriptTakesPrecedence() {
        ProjectMeetingEntity m = ProjectMeetingEntity.builder()
                .id(1L)
                .projectId(10L)
                .title("Strategy Discussion")
                .transcript("Transcript line 1\nTranscript line 2")
                .notes("Some notes")
                .build();

        when(meetingRepository.findByIdAndProjectId(1L, 10L)).thenReturn(Optional.of(m));
        when(chatMessageRepository.findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                eq(10L), any(), any(), any(Pageable.class))).thenReturn(Collections.emptyList());

        MeetingSourceTextDto sourceText = adapter.getMeetingSourceText(10L, 1L, 100L);
        assertThat(sourceText).isNotNull();
        assertThat(sourceText.sourceKind()).isEqualTo(MeetingSourceKind.TRANSCRIPT);
        assertThat(sourceText.transcript()).contains("Transcript line 1");
    }

    @Test
    void testSaveAiSummary_Success() {
        ProjectMeetingEntity m = ProjectMeetingEntity.builder()
                .id(1L)
                .projectId(10L)
                .title("Meeting")
                .build();

        when(meetingRepository.findByIdAndProjectId(1L, 10L)).thenReturn(Optional.of(m));

        adapter.saveAiSummary(10L, 1L, "{\"overview\":\"Done\"}", "NOTES", 100L);
        assertThat(m.getAiSummary()).isEqualTo("{\"overview\":\"Done\"}");
        assertThat(m.getAiSummaryGeneratedAt()).isNotNull();
        verify(meetingRepository).save(m);
    }
}
