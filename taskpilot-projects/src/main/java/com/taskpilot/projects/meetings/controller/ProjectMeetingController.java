package com.taskpilot.projects.meetings.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.common.enums.ProjectMeetingStatus;
import com.taskpilot.projects.meetings.dto.CreateMeetingRequest;
import com.taskpilot.projects.meetings.dto.MeetingParticipantResponse;
import com.taskpilot.projects.meetings.dto.MeetingTokenResponse;
import com.taskpilot.projects.meetings.dto.ProjectMeetingResponse;
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

    @Operation(summary = "Create project meeting", description = "Create a new meeting room in the project")
    @PostMapping
    public ApiResponse<ProjectMeetingResponse> createMeeting(
            @PathVariable Long projectId,
            Authentication authentication,
            @Valid @RequestBody CreateMeetingRequest request) {
        return ApiResponse.created("Meeting created successfully",
                meetingService.createMeeting(projectId, authentication.getName(), request));
    }

    @Operation(summary = "List project meetings", description = "Get list of meetings for project, optionally filtered by status")
    @GetMapping
    public ApiResponse<List<ProjectMeetingResponse>> getMeetings(
            @PathVariable Long projectId,
            Authentication authentication,
            @RequestParam(required = false) ProjectMeetingStatus status) {
        return ApiResponse.ok("Meetings retrieved successfully",
                meetingService.getMeetings(projectId, authentication.getName(), status));
    }

    @Operation(summary = "Get active project meeting", description = "Get current active meeting in project if one exists")
    @GetMapping("/active")
    public ApiResponse<ProjectMeetingResponse> getActiveMeeting(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.ok("Active meeting retrieved successfully",
                meetingService.getActiveMeeting(projectId, authentication.getName()).orElse(null));
    }

    @Operation(summary = "Get meeting details", description = "Get detail of a specific meeting")
    @GetMapping("/{meetingId}")
    public ApiResponse<ProjectMeetingResponse> getMeetingById(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Meeting retrieved successfully",
                meetingService.getMeetingById(projectId, meetingId, authentication.getName()));
    }

    @Operation(summary = "Join meeting and get LiveKit token", description = "Join meeting room and acquire signed LiveKit access token")
    @PostMapping("/{meetingId}/join")
    public ApiResponse<MeetingTokenResponse> joinMeeting(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Joined meeting successfully",
                meetingService.joinMeeting(projectId, meetingId, authentication.getName()));
    }

    @Operation(summary = "Leave meeting", description = "Record participant departure from meeting")
    @PostMapping("/{meetingId}/leave")
    public ApiResponse<Void> leaveMeeting(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        meetingService.leaveMeeting(projectId, meetingId, authentication.getName());
        return ApiResponse.ok("Left meeting successfully", null);
    }

    @Operation(summary = "End meeting", description = "End meeting for all participants (Host or Project Manager only)")
    @PatchMapping("/{meetingId}/end")
    public ApiResponse<ProjectMeetingResponse> endMeeting(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Meeting ended successfully",
                meetingService.endMeeting(projectId, meetingId, authentication.getName()));
    }

    @Operation(summary = "List meeting participants", description = "Get participants who joined the meeting")
    @GetMapping("/{meetingId}/participants")
    public ApiResponse<List<MeetingParticipantResponse>> getParticipants(
            @PathVariable Long projectId,
            @PathVariable Long meetingId,
            Authentication authentication) {
        return ApiResponse.ok("Participants retrieved successfully",
                meetingService.getParticipants(projectId, meetingId, authentication.getName()));
    }
}
