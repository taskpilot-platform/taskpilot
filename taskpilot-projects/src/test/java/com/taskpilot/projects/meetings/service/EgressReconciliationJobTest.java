package com.taskpilot.projects.meetings.service;

import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
        reconciliationJob = new EgressReconciliationJob(meetingRepository, egressClientAdapter, meetingRecordingService, 24L);
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
}
