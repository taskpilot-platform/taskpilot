package com.taskpilot.projects.meetings.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.meetings.dto.ProjectMeetingResponse;
import com.taskpilot.projects.meetings.service.ProjectMeetingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "17.1 Global Meetings", description = "Cross-project meeting queries for Calendar & Agenda")
@RestController
@RequestMapping({"/api/v1/meetings", "/api/meetings"})
@RequiredArgsConstructor
public class GlobalMeetingController {

    private final ProjectMeetingService meetingService;

    @Operation(summary = "Get my meetings across all projects", description = "Retrieves all meetings across projects the user belongs to for calendar display")
    @GetMapping("/my")
    public ApiResponse<List<ProjectMeetingResponse>> getMyMeetings(Authentication authentication) {
        return ApiResponse.ok("User meetings retrieved successfully",
                meetingService.getMyMeetings(authentication.getName()));
    }
}
