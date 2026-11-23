package com.taskpilot.projects.meetings.service;

import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectMeetingEntity;
import com.taskpilot.projects.common.entity.ProjectMeetingParticipantEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MeetingRecordingStatus;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.common.repository.ProjectMeetingParticipantRepository;
import com.taskpilot.projects.common.repository.ProjectMeetingRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.meetings.dto.CreateMeetingRequest;
import com.taskpilot.projects.meetings.dto.MeetingParticipantResponse;
import com.taskpilot.projects.meetings.dto.MeetingTokenResponse;
import com.taskpilot.projects.meetings.dto.ProjectMeetingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMeetingService {

    private final ProjectMeetingRepository meetingRepository;
    private final ProjectMeetingParticipantRepository participantRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final LiveKitTokenService liveKitTokenService;
    private final UserProfilePort userProfilePort;
    private final MeetingRoomGateway roomGateway;
    private final MeetingRoomLifecycle roomLifecycle;
    private final ClientMeetingRecordingService recordingService;

    private MeetingRecordingService meetingRecordingService;

    @Autowired(required = false)
    public void setMeetingRecordingService(@Lazy MeetingRecordingService meetingRecordingService) {
        this.meetingRecordingService = meetingRecordingService;
    }

    @Transactional
    public ProjectMeetingResponse createMeeting(Long projectId, Long userId, CreateMeetingRequest request) {
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        String roomName = "tp-proj-" + projectId + "-m-" + UUID.randomUUID().toString();

        boolean isScheduled = request.scheduledStartTime() != null 
                && request.scheduledStartTime().isAfter(Instant.now());
        ProjectMeetingStatus initialStatus = isScheduled 
                ? ProjectMeetingStatus.SCHEDULED 
                : ProjectMeetingStatus.ACTIVE;
        Instant startedAt = isScheduled ? null : Instant.now();

        ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                .projectId(projectId)
                .hostId(userId)
                .title(request.title().trim())
                .description(request.description() != null ? request.description().trim() : null)
                .roomName(roomName)
                .status(initialStatus)
                .recordingEnabled(Boolean.TRUE.equals(request.recordingEnabled()))
                .scheduledStartTime(request.scheduledStartTime())
                .scheduledEndTime(request.scheduledEndTime())
                .startedAt(startedAt)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        ProjectMeetingEntity saved = meetingRepository.save(meeting);

        return mapToResponse(saved, userId);
    }

    @Transactional(readOnly = true)
    public List<ProjectMeetingResponse> getMeetings(Long projectId, Long userId, ProjectMeetingStatus status) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        List<ProjectMeetingEntity> entities;
        if (status != null) {
            entities = meetingRepository.findByProjectIdAndStatusOrderByStartedAtDesc(projectId, status);
        } else {
            entities = meetingRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        }

        return entities.stream()
                .map(m -> mapToResponse(m, userId))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<ProjectMeetingResponse> getActiveMeeting(Long projectId, Long userId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        return meetingRepository.findFirstByProjectIdAndStatusOrderByStartedAtDesc(projectId, ProjectMeetingStatus.ACTIVE)
                .map(m -> mapToResponse(m, userId));
    }

    @Transactional(readOnly = true)
    public ProjectMeetingResponse getMeetingById(Long projectId, Long meetingId, Long userId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));

        return mapToResponse(meeting, userId);
    }

    @Transactional
    public MeetingTokenResponse joinMeeting(Long projectId, Long meetingId, CurrentActor actor) {
        Long userId = actor.userId();
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findForUpdate(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(404, "Meeting not found"));

        if (meeting.getStatus() == ProjectMeetingStatus.ENDED || meeting.getStatus() == ProjectMeetingStatus.ENDING) {
            throw new BusinessException(409, "This meeting has ended or is closing");
        }
        boolean isHost = Objects.equals(meeting.getHostId(), userId) || isProjectManager(projectId, userId);

        UserProfileLiteDto profile = userProfilePort.findLiteById(userId).orElse(null);
        String displayName = profile != null && profile.fullName() != null && !profile.fullName().isBlank()
                ? profile.fullName()
                : "User #" + userId;
        String identity = "user_" + userId;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", userId);
        metadata.put("role", isHost ? "HOST" : "PARTICIPANT");
        if (profile != null && profile.avatarUrl() != null) {
            metadata.put("avatarUrl", profile.avatarUrl());
        }

        String token = liveKitTokenService.createAccessToken(
                meeting.getRoomName(),
                identity,
                displayName,
                isHost,
                metadata
        );

        if (roomGateway.ensureRoom(meeting.getRoomName()) != MeetingRoomGateway.Outcome.SUCCESS) {
            throw new BusinessException(503, "LiveKit is unavailable");
        }

        ProjectMeetingResponse meetingResponse = mapToResponse(meeting, userId);

        return MeetingTokenResponse.builder()
                .token(token)
                .livekitUrl(liveKitTokenService.getLivekitUrl())
                .roomName(meeting.getRoomName())
                .identity(identity)
                .participantName(displayName)
                .isHost(isHost)
                .meeting(meetingResponse)
                .build();
    }

    public void leaveMeeting(Long projectId, Long meetingId, Long userId, String participantSid) {
        roomLifecycle.leave(projectId, meetingId, userId, participantSid);
    }

    public void reconcileVerifiedRoomEvent(String roomName) {
        roomLifecycle.reconcileRoomFromProvider(roomName);
    }

    public void confirmAttendance(Long projectId, Long meetingId, Long userId, String participantSid) {
        roomLifecycle.confirmAttendance(projectId, meetingId, userId, participantSid);
    }

    public ProjectMeetingResponse endMeeting(Long projectId, Long meetingId, Long userId) {
        roomLifecycle.requestEnd(projectId, meetingId, userId);
        if (meetingRecordingService != null) {
            meetingRepository.findByIdAndProjectId(meetingId, projectId)
                    .ifPresent(meetingRecordingService::stopRecordingIfActive);
        }
        return getMeetingById(projectId, meetingId, userId);
    }

    public ProjectMeetingResponse updateRecordingFile(Long projectId, Long meetingId, Long userId, Long recordingFileId) {
        recordingService.linkLegacy(projectId, meetingId, userId, recordingFileId);
        return getMeetingById(projectId, meetingId, userId);
    }

    @Transactional(readOnly = true)
    public List<MeetingParticipantResponse> getParticipants(Long projectId, Long meetingId, Long userId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);
        meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(404, "Meeting not found"));

        List<ProjectMeetingParticipantEntity> participants =
                participantRepository.findByMeetingIdOrderByJoinedAtAsc(meetingId);

        Set<Long> userIds = participants.stream()
                .map(ProjectMeetingParticipantEntity::getUserId)
                .collect(Collectors.toSet());

        Map<Long, UserProfileLiteDto> profiles = userProfilePort.findLiteByIds(userIds).stream()
                .collect(Collectors.toMap(UserProfileLiteDto::id, p -> p, (a, b) -> a));

        return participants.stream().map(p -> {
            UserProfileLiteDto prof = profiles.get(p.getUserId());
            String name = prof != null && prof.fullName() != null ? prof.fullName() : "User #" + p.getUserId();
            String avatarUrl = prof != null ? prof.avatarUrl() : null;

            return MeetingParticipantResponse.builder()
                    .id(p.getId())
                    .meetingId(p.getMeetingId())
                    .userId(p.getUserId())
                    .name(name)
                    .avatarUrl(avatarUrl)
                    .role(p.getRole())
                    .joinedAt(p.getJoinedAt())
                    .leftAt(p.getLeftAt())
                    .isActive(p.getLeftAt() == null)
                    .build();
        }).collect(Collectors.toList());
    }

    private ProjectMeetingResponse mapToResponse(ProjectMeetingEntity entity, Long currentUserId) {
        UserProfileLiteDto hostProfile = entity.getHostId() != null
                ? userProfilePort.findLiteById(entity.getHostId()).orElse(null)
                : null;

        String hostName = hostProfile != null && hostProfile.fullName() != null
                ? hostProfile.fullName()
                : (entity.getHostId() != null ? "User #" + entity.getHostId() : "System");
        String hostAvatar = hostProfile != null ? hostProfile.avatarUrl() : null;

        long activeCount = participantRepository.countByMeetingIdAndLeftAtIsNull(entity.getId());
        long totalCount = participantRepository.countByMeetingId(entity.getId());

        Long durationSeconds = null;
        if (entity.getStartedAt() != null) {
            Instant end = entity.getEndedAt() != null ? entity.getEndedAt() : Instant.now();
            durationSeconds = Duration.between(entity.getStartedAt(), end).getSeconds();
        }

        boolean isHost = Objects.equals(entity.getHostId(), currentUserId) || isProjectManager(entity.getProjectId(), currentUserId);

        return ProjectMeetingResponse.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .hostId(entity.getHostId())
                .hostName(hostName)
                .hostAvatarUrl(hostAvatar)
                .title(entity.getTitle())
                .description(entity.getDescription())
                .roomName(entity.getRoomName())
                .status(entity.getStatus())
                .recordingEnabled(entity.getRecordingEnabled())
                .recordingFileId(entity.getRecordingFileId())
                .egressId(entity.getEgressId())
                .recordingStatus(entity.getRecordingStatus() != null ? entity.getRecordingStatus() : MeetingRecordingStatus.IDLE)
                .recordingError(entity.getRecordingError())
                .backupStorageUsed(Boolean.TRUE.equals(entity.getBackupStorageUsed()))
                .scheduledStartTime(entity.getScheduledStartTime())
                .scheduledEndTime(entity.getScheduledEndTime())
                .startedAt(entity.getStartedAt())
                .endedAt(entity.getEndedAt())
                .durationSeconds(durationSeconds)
                .activeParticipantsCount(activeCount)
                .totalParticipantsCount(totalCount)
                .isHost(isHost)
                .build();
    }

    @Transactional(readOnly = true)
    public List<ProjectMeetingResponse> getMyMeetings(Long userId) {
        List<ProjectMemberEntity> memberships = projectMemberRepository.findByUserId(userId);
        if (memberships.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> projectIds = memberships.stream().map(ProjectMemberEntity::getProjectId).distinct().toList();
        return meetingRepository.findByProjectIdInOrderByCreatedAtDesc(projectIds)
                .stream()
                .map(m -> mapToResponse(m, userId))
                .collect(Collectors.toList());
    }

    private boolean isProjectManager(Long projectId, Long userId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(m -> m.getRole() == MemberRole.MANAGER)
                .orElse(false);
    }
}
