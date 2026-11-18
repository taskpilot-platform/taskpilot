package com.taskpilot.projects.meetings.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingParticipantEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectMeetingRole;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.ProjectMeetingParticipantRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingRoomLifecycle {
    private static final Duration LEGACY_TOKEN_WINDOW = Duration.ofHours(12).plusMinutes(5);

    private final ProjectMeetingRepository meetings;
    private final ProjectMeetingParticipantRepository attendance;
    private final ProjectMemberRepository members;
    private final ProjectSecurityService security;
    private final MeetingRoomGateway gateway;
    private final TransactionTemplate transactions;

    public void confirmAttendance(Long projectId, Long meetingId, Long userId, String sid) {
        transactions.executeWithoutResult(transaction -> {
            security.requireActiveProject(projectId);
            security.validateMember(projectId, userId);
            ProjectMeetingEntity meeting = lock(projectId, meetingId);
            if (meeting.getStatus() == ProjectMeetingStatus.ENDING || meeting.getStatus() == ProjectMeetingStatus.ENDED) {
                throw new BusinessException(409, "The meeting has ended or is closing");
            }

            String identity = "user_" + userId;
            MeetingRoomGateway.Participants result = gateway.listParticipants(meeting.getRoomName());
            if (result.outcome() == MeetingRoomGateway.Outcome.RETRYABLE
                    || result.outcome() == MeetingRoomGateway.Outcome.PERMANENT) {
                throw new BusinessException(503, "Attendance verification is temporarily unavailable");
            }
            if (result.outcome() != MeetingRoomGateway.Outcome.SUCCESS || result.values().stream()
                    .noneMatch(participant -> participant.identity().equals(identity) && participant.sid().equals(sid))) {
                throw new BusinessException(409, "LiveKit has not confirmed this session");
            }

            Instant now = Instant.now();
            var current = attendance.findFirstByMeetingIdAndUserIdAndLeftAtIsNullOrderByJoinedAtDesc(meetingId, userId);
            if (current.isEmpty() || !Objects.equals(current.get().getProviderSid(), sid)) {
                current.ifPresent(previous -> {
                    previous.setLeftAt(now);
                    attendance.saveAndFlush(previous);
                });
                attendance.save(ProjectMeetingParticipantEntity.builder()
                        .meetingId(meetingId)
                        .userId(userId)
                        .providerSid(sid)
                        .role(canManage(meeting, userId) ? ProjectMeetingRole.HOST : ProjectMeetingRole.PARTICIPANT)
                        .joinedAt(now)
                        .build());
            }
            if (meeting.getStatus() == ProjectMeetingStatus.SCHEDULED) {
                meeting.setStatus(ProjectMeetingStatus.ACTIVE);
                meeting.setStartedAt(now);
            }
            if (meeting.getRoomCleanupAt() == null) meeting.setRoomCleanupAt(now.plusSeconds(45));
        });
    }

    public void leave(Long projectId, Long meetingId, Long userId, String sid) {
        transactions.executeWithoutResult(transaction -> {
            security.validateMember(projectId, userId);
            lock(projectId, meetingId);
            attendance.findFirstByMeetingIdAndUserIdAndLeftAtIsNullOrderByJoinedAtDesc(meetingId, userId)
                    .filter(row -> Objects.equals(row.getProviderSid(), sid))
                    .ifPresent(row -> row.setLeftAt(Instant.now()));
        });
    }

    public void requestEnd(Long projectId, Long meetingId, Long userId) {
        boolean attempt = Boolean.TRUE.equals(transactions.execute(transaction -> {
            security.requireActiveProject(projectId);
            security.validateMember(projectId, userId);
            ProjectMeetingEntity meeting = lock(projectId, meetingId);
            if (!canManage(meeting, userId)) {
                throw new BusinessException(403, "Only the meeting host or project manager can end the meeting");
            }
            if (meeting.getStatus() == ProjectMeetingStatus.ENDED) return false;
            if (meeting.getStatus() != ProjectMeetingStatus.ENDING) {
                Instant now = Instant.now();
                meeting.setStatus(ProjectMeetingStatus.ENDING);
                meeting.setEndingRequestedAt(now);
                meeting.setRoomCleanupAt(now);
            }
            return true;
        }));
        if (attempt) reconcile(projectId, meetingId);
    }

    @Scheduled(fixedDelay = 15_000)
    void retryDueRooms() {
        for (ProjectMeetingEntity meeting : meetings.findTop5ByRoomCleanupAtBeforeOrderByRoomCleanupAtAsc(Instant.now())) {
            try {
                reconcile(meeting.getProjectId(), meeting.getId());
            } catch (RuntimeException ex) {
                log.warn("Meeting reconciliation failed for meeting {}", meeting.getId());
            }
        }
    }

    public void reconcileRoomFromProvider(String room) {
        meetings.findByRoomName(room).ifPresent(meeting -> {
            if (meeting.getStatus() == ProjectMeetingStatus.ENDING || meeting.getStatus() == ProjectMeetingStatus.ENDED
                    || meeting.getStatus() == ProjectMeetingStatus.ACTIVE) {
                reconcile(meeting.getProjectId(), meeting.getId());
            }
        });
    }

    private void reconcile(Long projectId, Long meetingId) {
        transactions.executeWithoutResult(transaction -> {
            ProjectMeetingEntity meeting = meetings.findForUpdate(meetingId, projectId).orElse(null);
            if (meeting == null) return;
            Instant now = Instant.now();
            if (meeting.getStatus() == ProjectMeetingStatus.ENDING || meeting.getStatus() == ProjectMeetingStatus.ENDED) {
                reconcileEnding(meeting, now);
            } else if (meeting.getStatus() == ProjectMeetingStatus.ACTIVE) {
                reconcileAttendance(meeting, now);
            }
        });
    }

    private void reconcileEnding(ProjectMeetingEntity meeting, Instant now) {
        boolean ended = meeting.getStatus() == ProjectMeetingStatus.ENDED;
        if (ended && meeting.getEndedAt() != null
                && now.isAfter(meeting.getEndedAt().plus(LEGACY_TOKEN_WINDOW))) {
            meeting.setRoomCleanupAt(null);
            return;
        }

        MeetingRoomGateway.Outcome outcome = gateway.terminateRoom(meeting.getRoomName());
        if (outcome == MeetingRoomGateway.Outcome.SUCCESS || outcome == MeetingRoomGateway.Outcome.NOT_FOUND) {
            if (!ended) {
                meeting.setStatus(ProjectMeetingStatus.ENDED);
                meeting.setEndedAt(now);
                for (var row : activeAttendance(meeting)) row.setLeftAt(now);
            }
            meeting.setRoomCleanupAt(now.plus(Duration.ofMinutes(5)));
        } else {
            log.warn("Meeting provider termination pending for meeting {} ({})", meeting.getId(), outcome);
            meeting.setRoomCleanupAt(now.plusSeconds(outcome == MeetingRoomGateway.Outcome.PERMANENT ? 120 : 20));
        }
    }

    private void reconcileAttendance(ProjectMeetingEntity meeting, Instant now) {
        List<ProjectMeetingParticipantEntity> joined = activeAttendance(meeting);
        if (joined.isEmpty()) {
            meeting.setRoomCleanupAt(null);
            return;
        }

        var result = gateway.listParticipants(meeting.getRoomName());
        if (result.outcome() == MeetingRoomGateway.Outcome.SUCCESS || result.outcome() == MeetingRoomGateway.Outcome.NOT_FOUND) {
            for (var row : joined) {
                boolean stillConnected = result.values().stream().anyMatch(p ->
                        p.identity().equals("user_" + row.getUserId()) && p.sid().equals(row.getProviderSid()));
                if (!stillConnected) row.setLeftAt(now);
            }
        }
        meeting.setRoomCleanupAt(now.plusSeconds(45));
    }

    private List<ProjectMeetingParticipantEntity> activeAttendance(ProjectMeetingEntity meeting) {
        return attendance.findByMeetingIdAndLeftAtIsNullOrderByJoinedAtAsc(meeting.getId());
    }

    private ProjectMeetingEntity lock(Long projectId, Long meetingId) {
        return meetings.findForUpdate(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(404, "Meeting not found"));
    }

    private boolean canManage(ProjectMeetingEntity meeting, Long userId) {
        return Objects.equals(meeting.getHostId(), userId)
                || members.findByProjectIdAndUserId(meeting.getProjectId(), userId)
                        .map(member -> member.getRole() == MemberRole.MANAGER).orElse(false);
    }
}
