package com.taskpilot.projects.meetings.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final UserIdentityPort userIdentityPort;
    private final UserProfilePort userProfilePort;

    @Transactional
    public ProjectMeetingResponse createMeeting(Long projectId, String userEmail, CreateMeetingRequest request) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        String roomName = "tp-proj-" + projectId + "-m-" + UUID.randomUUID().toString().substring(0, 8);

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

        // Record host as the first participant
        ProjectMeetingParticipantEntity hostParticipant = ProjectMeetingParticipantEntity.builder()
                .meetingId(saved.getId())
                .userId(userId)
                .role(ProjectMeetingRole.HOST)
                .joinedAt(Instant.now())
                .build();
        participantRepository.save(hostParticipant);

        return mapToResponse(saved, userId);
    }

    @Transactional(readOnly = true)
    public List<ProjectMeetingResponse> getMeetings(Long projectId, String userEmail, ProjectMeetingStatus status) {
        Long userId = getUserId(userEmail);
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
    public Optional<ProjectMeetingResponse> getActiveMeeting(Long projectId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        return meetingRepository.findFirstByProjectIdAndStatusOrderByStartedAtDesc(projectId, ProjectMeetingStatus.ACTIVE)
                .map(m -> mapToResponse(m, userId));
    }

    @Transactional(readOnly = true)
    public ProjectMeetingResponse getMeetingById(Long projectId, Long meetingId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));

        return mapToResponse(meeting, userId);
    }

    @Transactional
    public MeetingTokenResponse joinMeeting(Long projectId, Long meetingId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));

        if (meeting.getStatus() == ProjectMeetingStatus.ENDED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "This meeting has already ended");
        }

        if (meeting.getStatus() == ProjectMeetingStatus.SCHEDULED) {
            meeting.setStatus(ProjectMeetingStatus.ACTIVE);
            if (meeting.getStartedAt() == null) {
                meeting.setStartedAt(Instant.now());
            }
            meetingRepository.save(meeting);
        }

        boolean isHost = Objects.equals(meeting.getHostId(), userId) || isProjectManager(projectId, userId);

        // Record participant entry if not already actively joined
        Optional<ProjectMeetingParticipantEntity> existing = participantRepository
                .findFirstByMeetingIdAndUserIdAndLeftAtIsNullOrderByJoinedAtDesc(meetingId, userId);

        if (existing.isEmpty()) {
            ProjectMeetingParticipantEntity participant = ProjectMeetingParticipantEntity.builder()
                    .meetingId(meetingId)
                    .userId(userId)
                    .role(isHost ? ProjectMeetingRole.HOST : ProjectMeetingRole.PARTICIPANT)
                    .joinedAt(Instant.now())
                    .build();
            participantRepository.save(participant);
        }

        UserProfileLiteDto profile = userProfilePort.findLiteById(userId).orElse(null);
        String displayName = profile != null && profile.fullName() != null && !profile.fullName().isBlank()
                ? profile.fullName()
                : "User #" + userId;
        String identity = "user_" + userId;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", userId);
        metadata.put("email", userEmail);
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

    @Transactional
    public void leaveMeeting(Long projectId, Long meetingId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.validateMember(projectId, userId);

        participantRepository.findFirstByMeetingIdAndUserIdAndLeftAtIsNullOrderByJoinedAtDesc(meetingId, userId)
                .ifPresent(p -> {
                    p.setLeftAt(Instant.now());
                    participantRepository.save(p);
                });
    }

    @Transactional
    public ProjectMeetingResponse endMeeting(Long projectId, Long meetingId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireActiveProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting not found"));

        boolean isHost = Objects.equals(meeting.getHostId(), userId) || isProjectManager(projectId, userId);
        if (!isHost) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Only the meeting host or project manager can end the meeting");
        }

        if (meeting.getStatus() != ProjectMeetingStatus.ENDED) {
            meeting.setStatus(ProjectMeetingStatus.ENDED);
            meeting.setEndedAt(Instant.now());
            meetingRepository.save(meeting);

            // Close all active participants
            List<ProjectMeetingParticipantEntity> activeParticipants =
                    participantRepository.findByMeetingIdAndLeftAtIsNullOrderByJoinedAtAsc(meetingId);
            Instant now = Instant.now();
            activeParticipants.forEach(p -> p.setLeftAt(now));
            participantRepository.saveAll(activeParticipants);
        }

        return mapToResponse(meeting, userId);
    }

    @Transactional(readOnly = true)
    public List<MeetingParticipantResponse> getParticipants(Long projectId, Long meetingId, String userEmail) {
        Long userId = getUserId(userEmail);
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

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
    public List<ProjectMeetingResponse> getMyMeetings(String userEmail) {
        Long userId = getUserId(userEmail);
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

    private Long getUserId(String email) {
        return userIdentityPort.findByEmail(email)
                .map(u -> u.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }
}
