package com.taskpilot.projects.meetings.service;

import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EgressReconciliationJobTest {

    @Mock
    private ProjectMeetingRepository meetingRepository;
    @Mock
    private LiveKitEgressClientAdapter egressClientAdapter;
    @Mock
    private MeetingRecordingService meetingRecordingService;

    private EgressReconciliationJob reconciliationJob;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        reconciliationJob = new EgressReconciliationJob(meetingRepository, egressClientAdapter, meetingRecordingService, 24L, 120L);
        lenient().when(meetingRepository.findByRecordingStatusAndEgressIdIsNullAndUpdatedAtBefore(
                eq(MeetingRecordingStatus.STARTING), any(Instant.class))).thenReturn(List.of());
    }

    @Test
    void reconcileInFlightRecordings_pollsListEgressAndAppliesSnapshot() throws IOException {
        ProjectMeetingEntity stuck = ProjectMeetingEntity.builder()
                .id(9L)
                .projectId(2L)
                .egressId("EG_STUCK")
                .recordingStatus(MeetingRecordingStatus.PROCESSING)
                .build();
        EgressSnapshot completed = new EgressSnapshot(
                "EG_STUCK", "tp-proj-2-m-1", "EGRESS_COMPLETE", "", false,
                "recordings/project-2/meeting-9.mp4", 9999L, 5000L
        );

        when(egressClientAdapter.isConfigured()).thenReturn(true);
        when(meetingRepository.findByRecordingStatusInAndEgressIdIsNotNull(anyCollection())).thenReturn(List.of(stuck));
        when(egressClientAdapter.listEgress(null, "EG_STUCK")).thenReturn(List.of(completed));

        int count = reconciliationJob.reconcileInFlightRecordings();

        assertThat(count).isEqualTo(1);
        verify(meetingRecordingService).applyEgressSnapshot(completed);
    }

    @Test
    void reconcileInFlightRecordings_marksExpiredVanishedEgressFailedInsteadOfLoopingForever() throws IOException {
        ProjectMeetingEntity vanished = ProjectMeetingEntity.builder()
                .id(12L)
                .projectId(2L)
                .egressId("EG_VANISHED")
                .recordingStatus(MeetingRecordingStatus.PROCESSING)
                .updatedAt(Instant.now().minus(Duration.ofHours(30)))
                .build();

        when(egressClientAdapter.isConfigured()).thenReturn(true);
        when(meetingRepository.findByRecordingStatusInAndEgressIdIsNotNull(anyCollection())).thenReturn(List.of(vanished));
        when(egressClientAdapter.listEgress(null, "EG_VANISHED")).thenReturn(List.of());

        int count = reconciliationJob.reconcileInFlightRecordings();

        assertThat(count).isEqualTo(1);
        verify(meetingRecordingService).markMissingEgressFailed(eq(12L), eq("EG_VANISHED"), contains("no longer found"));
    }

    @Test
    void reconcileInFlightRecordings_recoversOrReleasesStaleStartingClaimWithNullEgressId() throws IOException {
        ProjectMeetingEntity crashedWithLiveEgress = ProjectMeetingEntity.builder()
                .id(20L).projectId(2L).roomName("tp-proj-2-m-20")
                .egressId(null).recordingStatus(MeetingRecordingStatus.STARTING)
                .updatedAt(Instant.now().minusSeconds(300)).build();
        ProjectMeetingEntity crashedWithoutEgress = ProjectMeetingEntity.builder()
                .id(21L).projectId(2L).roomName("tp-proj-2-m-21")
                .egressId(null).recordingStatus(MeetingRecordingStatus.STARTING)
                .updatedAt(Instant.now().minusSeconds(300)).build();

        EgressSnapshot found = new EgressSnapshot(
                "EG_ORPHAN_20", "tp-proj-2-m-20", "EGRESS_ACTIVE", "", false, "", 0L, 0L
        );

        when(egressClientAdapter.isConfigured()).thenReturn(true);
        when(meetingRepository.findByRecordingStatusInAndEgressIdIsNotNull(anyCollection())).thenReturn(List.of());
        when(meetingRepository.findByRecordingStatusAndEgressIdIsNullAndUpdatedAtBefore(
                eq(MeetingRecordingStatus.STARTING), any(Instant.class)))
                .thenReturn(List.of(crashedWithLiveEgress, crashedWithoutEgress));
        when(egressClientAdapter.listEgress("tp-proj-2-m-20", null)).thenReturn(List.of(found));
        when(egressClientAdapter.listEgress("tp-proj-2-m-21", null)).thenReturn(List.of());

        int count = reconciliationJob.reconcileInFlightRecordings();

        assertThat(count).isEqualTo(2);
        verify(meetingRecordingService).recoverStartingClaim(20L, found);
        verify(meetingRecordingService).releaseStaleStartingClaim(eq(21L), contains("server restart"));
    }

    @Test
    void reconcileInFlightRecordings_stopsActiveEgressWhenMeetingIsEndingOrEnded() throws IOException {
        ProjectMeetingEntity endingWithLiveEgress = ProjectMeetingEntity.builder()
                .id(33L).projectId(2L).roomName("tp-proj-2-m-33")
                .status(ProjectMeetingStatus.ENDING)
                .egressId("EG_ENDING_RETRY").recordingStatus(MeetingRecordingStatus.RECORDING).build();
        EgressSnapshot stillActive = new EgressSnapshot(
                "EG_ENDING_RETRY", "tp-proj-2-m-33", "EGRESS_ACTIVE", "", false, "", 0L, 0L
        );

        when(egressClientAdapter.isConfigured()).thenReturn(true);
        when(meetingRepository.findByRecordingStatusInAndEgressIdIsNotNull(anyCollection()))
                .thenReturn(List.of(endingWithLiveEgress));
        when(egressClientAdapter.listEgress(null, "EG_ENDING_RETRY")).thenReturn(List.of(stillActive));

        int count = reconciliationJob.reconcileInFlightRecordings();

        assertThat(count).isEqualTo(1);
        verify(meetingRecordingService).applyEgressSnapshot(stillActive);
        verify(meetingRecordingService).stopRecordingIfActive(endingWithLiveEgress);
    }
}
