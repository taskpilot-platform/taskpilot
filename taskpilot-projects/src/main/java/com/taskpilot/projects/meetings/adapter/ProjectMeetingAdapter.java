package com.taskpilot.projects.meetings.adapter;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.contracts.workspace.chat.ChatMessageSummaryDto;
import com.taskpilot.contracts.workspace.meeting.MeetingDetailDto;
import com.taskpilot.contracts.workspace.meeting.MeetingListItemDto;
import com.taskpilot.contracts.workspace.meeting.MeetingParticipantDto;
import com.taskpilot.contracts.workspace.meeting.MeetingSourceKind;
import com.taskpilot.contracts.workspace.meeting.MeetingSourceTextDto;
import com.taskpilot.contracts.workspace.meeting.MeetingTranscriptionPort;
import com.taskpilot.contracts.workspace.meeting.ProjectMeetingPort;
import com.taskpilot.contracts.workspace.meeting.ScheduleMeetingCommand;
import com.taskpilot.infrastructure.exception.BusinessException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectMeetingAdapter implements ProjectMeetingPort, MeetingTranscriptionPort {

    private final ProjectMeetingRepository meetingRepository;
    private final ProjectMeetingParticipantRepository participantRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectChatMessageRepository chatMessageRepository;
    private final ProjectSecurityService projectSecurityService;
    private final UserProfilePort userProfilePort;

    @Override
    @Transactional(readOnly = true)
    public List<MeetingListItemDto> listMeetings(Long projectId, String status, int limit, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        int resolvedLimit = Math.min(Math.max(limit, 1), 50);
        List<ProjectMeetingEntity> meetings;

        if (status != null && !status.isBlank()) {
            try {
                ProjectMeetingStatus meetingStatus = ProjectMeetingStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
                meetings = meetingRepository.findByProjectIdAndStatusOrderByStartedAtDesc(projectId, meetingStatus);
            } catch (IllegalArgumentException e) {
                log.warn("[ProjectMeetingAdapter] Invalid meeting status '{}', returning all meetings", status);
                meetings = meetingRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
            }
        } else {
            meetings = meetingRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        }

        if (meetings.size() > resolvedLimit) {
            meetings = meetings.subList(0, resolvedLimit);
        }

        Map<Long, String> userNames = resolveUserNames(meetings.stream()
                .map(ProjectMeetingEntity::getHostId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        return meetings.stream()
                .map(m -> toListItemDto(m, userNames.getOrDefault(m.getHostId(), "Unknown")))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MeetingDetailDto getMeetingDetails(Long projectId, Long meetingId, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting #" + meetingId + " not found in project #" + projectId));

        String hostName = meeting.getHostId() != null
                ? userProfilePort.findLiteById(meeting.getHostId()).map(UserProfileLiteDto::fullName).orElse("Unknown")
                : "System";

        MeetingListItemDto listItem = toListItemDto(meeting, hostName);

        List<ProjectMeetingParticipantEntity> participants = participantRepository.findByMeetingIdOrderByJoinedAtAsc(meetingId);
        Set<Long> userIds = participants.stream().map(ProjectMeetingParticipantEntity::getUserId).collect(Collectors.toSet());
        Map<Long, String> userNames = resolveUserNames(userIds);

        List<MeetingParticipantDto> participantDtos = participants.stream()
                .map(p -> new MeetingParticipantDto(
                        p.getUserId(),
                        userNames.getOrDefault(p.getUserId(), "User #" + p.getUserId()),
                        p.getRole() != null ? p.getRole().name() : "PARTICIPANT",
                        p.getJoinedAt(),
                        p.getLeftAt()))
                .toList();

        Long recordingFileId = meeting.getRecordingFileId();
        String recordingFileName = null;
        String recordingAppRoute = null;

        if (recordingFileId != null) {
            Optional<ProjectFileEntity> fileOpt = projectFileRepository.findByIdAndProjectId(recordingFileId, projectId);
            if (fileOpt.isPresent()) {
                ProjectFileEntity f = fileOpt.get();
                recordingFileName = f.getOriginalName();
                recordingAppRoute = "/projects/" + projectId + "/files/" + f.getId();
            }
        }

        return new MeetingDetailDto(
                listItem,
                meeting.getDescription(),
                participantDtos,
                recordingFileId,
                recordingFileName,
                recordingAppRoute,
                meeting.getAiSummary());
    }

    @Override
    @Transactional
    public MeetingDetailDto scheduleMeeting(Long projectId, ScheduleMeetingCommand command, Long requesterUserId) {
        projectSecurityService.requireActiveProject(projectId);
        validateAccess(projectId, requesterUserId);

        if (command == null || command.title() == null || command.title().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Meeting title is required");
        }

        Instant start = command.start();
        Instant end = command.end();
        boolean isScheduled = start != null && start.isAfter(Instant.now());
        ProjectMeetingStatus status = isScheduled ? ProjectMeetingStatus.SCHEDULED : ProjectMeetingStatus.ACTIVE;
        Instant startedAt = isScheduled ? null : Instant.now();

        String roomName = "tp-proj-" + projectId + "-m-" + UUID.randomUUID().toString().substring(0, 8);

        ProjectMeetingEntity meeting = ProjectMeetingEntity.builder()
                .projectId(projectId)
                .hostId(requesterUserId)
                .title(command.title().trim())
                .description(command.description() != null ? command.description().trim() : null)
                .roomName(roomName)
                .status(status)
                .recordingEnabled(command.recordingEnabled())
                .scheduledStartTime(start)
                .scheduledEndTime(end)
                .startedAt(startedAt)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        ProjectMeetingEntity saved = meetingRepository.save(meeting);

        ProjectMeetingParticipantEntity hostParticipant = ProjectMeetingParticipantEntity.builder()
                .meetingId(saved.getId())
                .userId(requesterUserId)
                .role(ProjectMeetingRole.HOST)
                .joinedAt(Instant.now())
                .build();
        participantRepository.save(hostParticipant);

        return getMeetingDetails(projectId, saved.getId(), requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public MeetingSourceTextDto getMeetingSourceText(Long projectId, Long meetingId, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting #" + meetingId + " not found in project #" + projectId));

        String transcript = meeting.getTranscript();
        String notes = meeting.getNotes();
        String description = meeting.getDescription();

        Instant windowStart = meeting.getStartedAt() != null ? meeting.getStartedAt() : meeting.getCreatedAt();
        Instant windowEnd = meeting.getEndedAt() != null ? meeting.getEndedAt() : Instant.now();

        List<ProjectChatMessageEntity> chatEntities = chatMessageRepository.findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                projectId, windowStart, windowEnd, PageRequest.of(0, 100));

        Set<Long> senderIds = chatEntities.stream().map(ProjectChatMessageEntity::getSenderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> senderNames = resolveUserNames(senderIds);

        List<ChatMessageSummaryDto> chatMessages = chatEntities.stream()
                .map(msg -> new ChatMessageSummaryDto(
                        msg.getId(),
                        msg.getSenderId(),
                        senderNames.getOrDefault(msg.getSenderId(), "User #" + msg.getSenderId()),
                        msg.getContent(),
                        msg.getMessageType(),
                        msg.getFileId(),
                        null,
                        msg.getCreatedAt()))
                .toList();

        MeetingSourceKind sourceKind;
        if (transcript != null && !transcript.isBlank()) {
            sourceKind = MeetingSourceKind.TRANSCRIPT;
        } else if (notes != null && !notes.isBlank()) {
            sourceKind = MeetingSourceKind.NOTES;
        } else if (!chatMessages.isEmpty()) {
            sourceKind = MeetingSourceKind.CHAT_WINDOW;
        } else if (description != null && !description.isBlank()) {
            sourceKind = MeetingSourceKind.DESCRIPTION_ONLY;
        } else {
            sourceKind = MeetingSourceKind.NONE;
        }

        return new MeetingSourceTextDto(
                meeting.getId(),
                meeting.getTitle(),
                transcript,
                notes,
                description,
                chatMessages,
                sourceKind,
                meeting.getUpdatedAt());
    }

    @Override
    @Transactional
    public void saveAiSummary(Long projectId, Long meetingId, String summaryJson, String sourceKind, Long requesterUserId) {
        validateAccess(projectId, requesterUserId);

        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Meeting #" + meetingId + " not found in project #" + projectId));

        meeting.setAiSummary(summaryJson);
        meeting.setAiSummaryGeneratedAt(Instant.now());
        meetingRepository.save(meeting);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> transcribeRecording(Long projectId, Long meetingId) {
        ProjectMeetingEntity meeting = meetingRepository.findByIdAndProjectId(meetingId, projectId).orElse(null);
        if (meeting == null) {
            return Optional.empty();
        }
        if (meeting.getTranscript() != null && !meeting.getTranscript().isBlank()) {
            return Optional.of(meeting.getTranscript());
        }
        if (meeting.getRecordingFileId() != null) {
            return Optional.of("Transcript from recording file #" + meeting.getRecordingFileId()
                    + " for meeting '" + meeting.getTitle() + "'.");
        }
        return Optional.empty();
    }

    private void validateAccess(Long projectId, Long userId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);
    }

    private MeetingListItemDto toListItemDto(ProjectMeetingEntity m, String hostName) {
        return new MeetingListItemDto(
                m.getId(),
                m.getTitle(),
                m.getStatus() != null ? m.getStatus().name() : "SCHEDULED",
                m.getScheduledStartTime(),
                m.getScheduledEndTime(),
                m.getStartedAt(),
                m.getEndedAt(),
                m.getHostId(),
                hostName,
                m.getRecordingFileId() != null,
                m.getNotes() != null && !m.getNotes().isBlank(),
                m.getTranscript() != null && !m.getTranscript().isBlank(),
                m.getAiSummary() != null && !m.getAiSummary().isBlank());
    }

    private Map<Long, String> resolveUserNames(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return userProfilePort.findLiteByIds(userIds).stream()
                    .collect(Collectors.toMap(
                            UserProfileLiteDto::id,
                            u -> u.fullName() != null && !u.fullName().isBlank() ? u.fullName() : "User #" + u.id(),
                            (a, b) -> a));
        } catch (Exception e) {
            log.warn("[ProjectMeetingAdapter] Failed to resolve user names: {}", e.getMessage());
            Map<Long, String> fallback = new HashMap<>();
            for (Long uid : userIds) {
                fallback.put(uid, "User #" + uid);
            }
            return fallback;
        }
    }
}
