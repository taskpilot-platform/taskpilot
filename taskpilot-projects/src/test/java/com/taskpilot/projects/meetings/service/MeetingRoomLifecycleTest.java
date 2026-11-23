package com.taskpilot.projects.meetings.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingParticipantEntity;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.ProjectMeetingParticipantRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MeetingRoomLifecycleTest {
    private final ProjectMeetingRepository meetings = mock(ProjectMeetingRepository.class);
    private final ProjectMeetingParticipantRepository rows = mock(ProjectMeetingParticipantRepository.class);
    private final MeetingRoomGateway gateway = mock(MeetingRoomGateway.class);
    private final ProjectMeetingEntity meeting = ProjectMeetingEntity.builder().id(4L).projectId(2L)
            .hostId(7L).roomName("tp-proj-2-m-test").status(ProjectMeetingStatus.SCHEDULED).startedAt(null).build();
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final MeetingRoomLifecycle lifecycle = new MeetingRoomLifecycle(meetings, rows,
            mock(ProjectMemberRepository.class), mock(ProjectSecurityService.class), gateway, new TransactionTemplate(tx));

    @BeforeEach
    void arrange() {
        when(tx.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(meetings.findForUpdate(4L, 2L)).thenReturn(Optional.of(meeting));
    }

    @Test
    void aClientClaimAloneDoesNotStartOrJoinAScheduledMeeting() {
        when(gateway.listParticipants(meeting.getRoomName()))
                .thenReturn(new MeetingRoomGateway.Participants(MeetingRoomGateway.Outcome.SUCCESS,
                        List.of(new MeetingRoomGateway.Participant("user_9", "PA_other"))));
        var refused = assertThrows(BusinessException.class, () -> lifecycle.confirmAttendance(2L, 4L, 7L, "PA_other"));
        assertEquals(409, refused.getStatus());
        assertEquals(ProjectMeetingStatus.SCHEDULED, meeting.getStatus());
        assertNull(meeting.getStartedAt());
        verifyNoInteractions(rows);
    }

    @Test
    void aConfirmedSessionReplacesTheOldSessionAndAStaleLeaveCannotCloseIt() {
        var previous = ProjectMeetingParticipantEntity.builder().providerSid("PA_old").build();
        var current = ProjectMeetingParticipantEntity.builder().providerSid("PA_new").build();
        when(rows.findFirstByMeetingIdAndUserIdAndLeftAtIsNullOrderByJoinedAtDesc(4L, 7L))
                .thenReturn(Optional.of(previous), Optional.of(current));
        when(gateway.listParticipants(meeting.getRoomName())).thenReturn(
                new MeetingRoomGateway.Participants(MeetingRoomGateway.Outcome.SUCCESS,
                        List.of(new MeetingRoomGateway.Participant("user_7", "PA_new"))));

        lifecycle.confirmAttendance(2L, 4L, 7L, "PA_new");
        lifecycle.leave(2L, 4L, 7L, "PA_old");

        verify(rows).save(argThat(row -> row.getProviderSid().equals("PA_new") && row.getUserId().equals(7L)));
        assertNotNull(previous.getLeftAt());
        assertNull(current.getLeftAt());
        assertEquals(ProjectMeetingStatus.ACTIVE, meeting.getStatus());
        assertNotNull(meeting.getStartedAt());
    }

    @Test
    void providerFailureLeavesDurableEndingAndNotFoundCompletesItOnRetry() {
        var joined = ProjectMeetingParticipantEntity.builder().joinedAt(Instant.now().minusSeconds(30)).build();
        when(rows.findByMeetingIdAndLeftAtIsNullOrderByJoinedAtAsc(4L)).thenReturn(List.of(joined));
        when(gateway.terminateRoom(meeting.getRoomName()))
                .thenReturn(MeetingRoomGateway.Outcome.RETRYABLE, MeetingRoomGateway.Outcome.NOT_FOUND);
        when(meetings.findTop5ByRoomCleanupAtBeforeOrderByRoomCleanupAtAsc(any())).thenReturn(List.of(meeting));

        lifecycle.requestEnd(2L, 4L, 7L);
        assertEquals(ProjectMeetingStatus.ENDING, meeting.getStatus());
        assertNotNull(meeting.getRoomCleanupAt());
        assertNull(meeting.getEndedAt());
        assertNull(joined.getLeftAt());

        lifecycle.retryDueRooms();
        assertEquals(ProjectMeetingStatus.ENDED, meeting.getStatus());
        assertNotNull(meeting.getEndedAt());
        assertNotNull(joined.getLeftAt());
    }
}
