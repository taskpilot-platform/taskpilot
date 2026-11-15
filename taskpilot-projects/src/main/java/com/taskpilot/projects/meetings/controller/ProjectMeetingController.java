package com.taskpilot.projects.meetings.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.meetings.dto.CompleteRecordingUploadRequest;
import com.taskpilot.projects.meetings.dto.CreateMeetingRequest;
import com.taskpilot.projects.meetings.dto.MeetingParticipantResponse;
import com.taskpilot.projects.meetings.dto.MeetingRecordingPlaybackResponse;
import com.taskpilot.projects.meetings.dto.MeetingTokenResponse;
import com.taskpilot.projects.meetings.dto.PresignRecordingUploadRequest;
import com.taskpilot.projects.meetings.dto.PresignRecordingUploadResponse;
import com.taskpilot.projects.meetings.dto.ProjectMeetingResponse;
import com.taskpilot.projects.meetings.dto.UpdateMeetingRecordingRequest;
import com.taskpilot.projects.meetings.service.MeetingRecordingService;
import com.taskpilot.projects.meetings.service.ProjectMeetingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "17. Project Meetings", description = "APIs for project video conference and meetings powered by LiveKit")
@RestController
@RequestMapping({"/api/v1/projects/{projectId}/meetings", "/api/projects/{projectId}/meetings"})
@RequiredArgsConstructor
public class ProjectMeetingController {

    private final ProjectMeetingService meetingService;
    private final MeetingRecordingService meetingRecordingService;

    @Operation(summary = "Create project meeting", description = "Create a new meeting room in the project")
    @PostMapping
    public ApiResponse<ProjectMeetingResponse> createMeeting(
            @PathVariable Long projectId,
            Authentication authentication,
            @Valid @RequestBody CreateMeetingRequest request) {
        return ApiResponse.created("Meeting created successfully",
                meetingService.createMeeting(projectId, CurrentActorResolver.userId(authentication), request));
    }

    @Operation(summary = "List project meetings", description = "Get list of meetings for project, optionally filtered by status")
    @GetMapping
    public ApiResponse<List<ProjectMeetingResponse>> getMeetings(
            @PathVariable Long projectId,
            Authentication authentication,
            @RequestParam(required = false) ProjectMeetingStatus status) {
        return ApiResponse.ok("Meetings retrieved successfully",
                meetingService.getMeetings(projectId, CurrentActorResolver.userId(authentication), status));
    }

    @Operation(summary = "Get active project meeting", description = "Get current active meeting in project if one exists")
    @GetMapping("/active")
    public ApiResponse<ProjectMeetingResponse> getActiveMeeting(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.ok("Active meeting retrieved successfully",
                meetingService.getActiveMeeting(projectId, CurrentActorResolver.userId(authentication)).orElse(null));
    }

    @Operation(summary = "Get meeting details", description = "Get detail of a specific meeting")
    @GetMapping("/{meetingId}")
    public ApiResponse<ProjectMeetingResponse> getMeetingById(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Meeting retrieved successfully",
                meetingService.getMeetingById(projectId, meetingId, CurrentActorResolver.userId(authentication)));
    }

    @Operation(summary = "Join meeting and get LiveKit token", description = "Join meeting room and acquire signed LiveKit access token")
    @PostMapping("/{meetingId}/join")
    public ApiResponse<MeetingTokenResponse> joinMeeting(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Joined meeting successfully",
                meetingService.joinMeeting(projectId, meetingId, CurrentActorResolver.resolve(authentication)));
    }

    @Operation(summary = "Leave meeting", description = "Record participant departure from meeting")
    @PostMapping("/{meetingId}/leave")
    public ApiResponse<Void> leaveMeeting(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        meetingService.leaveMeeting(projectId, meetingId, CurrentActorResolver.userId(authentication));
        return ApiResponse.ok("Left meeting successfully", null);
    }

    @Operation(summary = "End meeting", description = "End meeting for all participants (Host or Project Manager only)")
    @PatchMapping("/{meetingId}/end")
    public ApiResponse<ProjectMeetingResponse> endMeeting(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Meeting ended successfully",
                meetingService.endMeeting(projectId, meetingId, CurrentActorResolver.userId(authentication)));
    }

    @Operation(summary = "Link meeting recording", description = "Attach an uploaded project file as the meeting recording")
    @PatchMapping("/{meetingId}/recording")
    public ApiResponse<ProjectMeetingResponse> updateRecording(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication,
            @Valid @RequestBody UpdateMeetingRecordingRequest request) {
        return ApiResponse.ok("Meeting recording linked successfully",
                meetingService.updateRecordingFile(projectId, meetingId, CurrentActorResolver.userId(authentication), request.recordingFileId()));
    }

    @Operation(summary = "Start cloud Egress recording", description = "Start LiveKit Cloud RoomComposite Egress recording to Backblaze B2 (Host or Project Manager only)")
    @PostMapping("/{meetingId}/recording/start")
    public ApiResponse<ProjectMeetingResponse> startRecording(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Cloud recording started successfully",
                meetingRecordingService.startRecording(projectId, meetingId, authentication.getName()));
    }

    @Operation(summary = "Stop cloud Egress recording", description = "Stop active LiveKit Cloud Egress recording (Host or Project Manager only)")
    @PostMapping("/{meetingId}/recording/stop")
    public ApiResponse<ProjectMeetingResponse> stopRecording(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Cloud recording stopped successfully",
                meetingRecordingService.stopRecording(projectId, meetingId, authentication.getName()));
    }

    @Operation(summary = "Synchronize cloud recording status", description = "Query LiveKit ListEgress to recover status if webhook was missed")
    @PostMapping("/{meetingId}/recording/sync")
    public ApiResponse<ProjectMeetingResponse> syncRecording(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Recording status synchronized successfully",
                meetingRecordingService.syncRecordingStatus(projectId, meetingId, authentication.getName()));
    }

    @Operation(summary = "Get recording playback URL", description = "Returns direct B2 presigned GET URL or backend Range-streaming proxy URL based on storage provider")
    @GetMapping("/{meetingId}/recording/playback-url")
    public ApiResponse<MeetingRecordingPlaybackResponse> getRecordingPlaybackUrl(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Recording playback URL retrieved successfully",
                meetingRecordingService.getPlaybackUrl(projectId, meetingId, authentication.getName()));
    }

    @Operation(summary = "Request presigned B2 PUT URL for fallback browser recording",
            description = "Server generates a scoped object key and short-lived Content-Type-locked B2 PUT URL (Host or Project Manager only)")
    @PostMapping("/{meetingId}/recording/presign-upload")
    public ApiResponse<PresignRecordingUploadResponse> presignFallbackUpload(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication,
            @Valid @RequestBody PresignRecordingUploadRequest request) {
        return ApiResponse.ok("Presigned recording upload URL generated",
                meetingRecordingService.presignFallbackUpload(projectId, meetingId, authentication.getName(), request));
    }

    @Operation(summary = "Complete fallback browser recording upload",
            description = "Verifies server-issued uploadToken, checks object size on B2 via HeadObject, and links the recording to the meeting")
    @PostMapping("/{meetingId}/recording/complete-upload")
    public ApiResponse<ProjectMeetingResponse> completeFallbackUpload(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication,
            @Valid @RequestBody CompleteRecordingUploadRequest request) {
        return ApiResponse.ok("Fallback recording completed and linked successfully",
                meetingRecordingService.completeFallbackUpload(projectId, meetingId, authentication.getName(), request));
    }

    @Operation(summary = "List meeting participants", description = "Get participants who joined the meeting")
    @GetMapping("/{meetingId}/participants")
    public ApiResponse<List<MeetingParticipantResponse>> getParticipants(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Participants retrieved successfully",
                meetingService.getParticipants(projectId, meetingId, CurrentActorResolver.userId(authentication)));
    }
}
