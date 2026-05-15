package com.taskpilot.projects.timeline.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.timeline.dto.TimelineResponse;
import com.taskpilot.projects.timeline.service.TimelineService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/timeline")
@RequiredArgsConstructor
public class TimelineController {

    private final TimelineService timelineService;

    @GetMapping
    public ApiResponse<TimelineResponse> getTimeline(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.success(HttpStatus.OK.value(), "Timeline retrieved successfully",
                timelineService.getTimeline(projectId, authentication.getName()));
    }
}
