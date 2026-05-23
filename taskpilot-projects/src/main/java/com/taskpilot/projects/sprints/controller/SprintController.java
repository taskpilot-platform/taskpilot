package com.taskpilot.projects.sprints.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.sprints.dto.BacklogResponse;
import com.taskpilot.projects.sprints.dto.BoardResponse;
import com.taskpilot.projects.sprints.dto.CreateSprintRequest;
import com.taskpilot.projects.sprints.dto.SprintDto;
import com.taskpilot.projects.sprints.dto.UpdateSprintRequest;
import com.taskpilot.projects.sprints.service.SprintService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@RequiredArgsConstructor
@Validated
public class SprintController {

    private final SprintService sprintService;

    @PostMapping("/sprints")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SprintDto> createSprint(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateSprintRequest request,
            Authentication authentication) {
        return ApiResponse.success(HttpStatus.CREATED.value(), "Sprint created successfully",
                sprintService.createSprint(projectId, request, authentication.getName()));
    }

    @GetMapping("/sprints")
    public ApiResponse<List<SprintDto>> listSprints(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.success(HttpStatus.OK.value(), "Sprints retrieved successfully",
                sprintService.listSprints(projectId, authentication.getName()));
    }

    @PutMapping("/sprints/{sprintId}")
    public ApiResponse<SprintDto> updateSprint(
            @PathVariable Long projectId,
            @PathVariable Long sprintId,
            @Valid @RequestBody UpdateSprintRequest request,
            Authentication authentication) {
        return ApiResponse.success(HttpStatus.OK.value(), "Sprint updated successfully",
                sprintService.updateSprint(projectId, sprintId, request, authentication.getName()));
    }

    @DeleteMapping("/sprints/{sprintId}")
    public ApiResponse<Void> deleteSprint(
            @PathVariable Long projectId,
            @PathVariable Long sprintId,
            Authentication authentication) {
        sprintService.deleteSprint(projectId, sprintId, authentication.getName());
        return ApiResponse.success(HttpStatus.OK.value(), "Sprint deleted successfully", null);
    }

    @PostMapping("/sprints/{sprintId}/start")
    public ApiResponse<SprintDto> startSprint(
            @PathVariable Long projectId,
            @PathVariable Long sprintId,
            Authentication authentication) {
        return ApiResponse.success(HttpStatus.OK.value(), "Sprint started successfully",
                sprintService.startSprint(projectId, sprintId, authentication.getName()));
    }

    @PostMapping("/sprints/{sprintId}/complete")
    public ApiResponse<SprintDto> completeSprint(
            @PathVariable Long projectId,
            @PathVariable Long sprintId,
            Authentication authentication) {
        return ApiResponse.success(HttpStatus.OK.value(), "Sprint completed successfully",
                sprintService.completeSprint(projectId, sprintId, authentication.getName()));
    }

    @GetMapping("/backlog")
    public ApiResponse<BacklogResponse> getBacklog(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.success(HttpStatus.OK.value(), "Backlog retrieved successfully",
                sprintService.getBacklog(projectId, authentication.getName()));
    }

    @GetMapping("/board")
    public ApiResponse<BoardResponse> getBoard(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.success(HttpStatus.OK.value(), "Board retrieved successfully",
                sprintService.getBoard(projectId, authentication.getName()));
    }
}
