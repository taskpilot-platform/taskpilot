package com.taskpilot.projects.sprints.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.sprints.dto.BacklogResponse;
import com.taskpilot.projects.sprints.dto.BoardResponse;
import com.taskpilot.projects.sprints.dto.CompleteSprintRequest;
import com.taskpilot.projects.sprints.dto.CreateSprintRequest;
import com.taskpilot.projects.sprints.dto.LegacyRepairDryRunManifestDto;
import com.taskpilot.projects.sprints.dto.SprintClosureSnapshotDto;
import com.taskpilot.projects.sprints.dto.SprintCompletePreviewResponse;
import com.taskpilot.projects.sprints.dto.SprintDto;
import com.taskpilot.projects.sprints.dto.UpdateSprintRequest;
import com.taskpilot.projects.sprints.service.LegacySprintRepairManifestService;
import com.taskpilot.projects.sprints.service.SprintService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@RequiredArgsConstructor
@Validated
public class SprintController {

    private final SprintService sprintService;
    private final LegacySprintRepairManifestService legacyRepairManifestService;

    @PostMapping("/sprints")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SprintDto> createSprint(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateSprintRequest request,
            Authentication authentication) {
        return ApiResponse.created("Sprint created successfully",
                sprintService.createSprint(projectId, request, CurrentActorResolver.userId(authentication)));
    }

    @GetMapping("/sprints")
    public ApiResponse<List<SprintDto>> listSprints(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.ok("Sprints retrieved successfully",
                sprintService.listSprints(projectId, CurrentActorResolver.userId(authentication)));
    }

    @PutMapping("/sprints/{sprintId}")
    public ApiResponse<SprintDto> updateSprint(
            @PathVariable Long projectId,
            @PathVariable Long sprintId,
            @Valid @RequestBody UpdateSprintRequest request,
            Authentication authentication) {
        return ApiResponse.ok("Sprint updated successfully",
                sprintService.updateSprint(projectId, sprintId, request, CurrentActorResolver.userId(authentication)));
    }

    @DeleteMapping("/sprints/{sprintId}")
    public ApiResponse<Void> deleteSprint(
            @PathVariable Long projectId,
            @PathVariable Long sprintId,
            Authentication authentication) {
        sprintService.deleteSprint(projectId, sprintId, CurrentActorResolver.userId(authentication));
        return ApiResponse.ok("Sprint deleted successfully", null);
    }

    @PostMapping("/sprints/{sprintId}/start")
    public ApiResponse<SprintDto> startSprint(
            @PathVariable Long projectId,
            @PathVariable Long sprintId,
            Authentication authentication) {
        return ApiResponse.ok("Sprint started successfully",
                sprintService.startSprint(projectId, sprintId, CurrentActorResolver.userId(authentication)));
    }

    @GetMapping("/sprints/{sprintId}/complete-preview")
    public ApiResponse<SprintCompletePreviewResponse> getCompletePreview(
            @PathVariable Long projectId,
            @PathVariable Long sprintId,
            Authentication authentication) {
        return ApiResponse.ok("Sprint completion preview retrieved",
                sprintService.getCompletePreview(projectId, sprintId, CurrentActorResolver.userId(authentication)));
    }

    @PostMapping("/sprints/{sprintId}/complete")
    public ApiResponse<SprintDto> completeSprint(
            @PathVariable Long projectId,
            @PathVariable Long sprintId,
            @RequestBody(required = false) CompleteSprintRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        UUID headerRequestId = parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        return ApiResponse.ok("Sprint completed successfully",
                sprintService.completeSprint(
                        projectId,
                        sprintId,
                        request,
                        CurrentActorResolver.userId(authentication),
                        headerRequestId));
    }

    @GetMapping("/sprints/{sprintId}/closure-snapshot")
    public ApiResponse<SprintClosureSnapshotDto> getClosureSnapshot(
            @PathVariable Long projectId,
            @PathVariable Long sprintId,
            Authentication authentication) {
        return ApiResponse.ok("Sprint closure snapshot retrieved",
                sprintService.getClosureSnapshot(projectId, sprintId, CurrentActorResolver.userId(authentication)));
    }

    @GetMapping("/sprints/legacy-repair-manifest")
    public ApiResponse<LegacyRepairDryRunManifestDto> getLegacyRepairManifest(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.ok("Legacy sprint repair dry-run manifest generated (read-only)",
                legacyRepairManifestService.generateProjectDryRunManifest(
                        projectId, CurrentActorResolver.userId(authentication)));
    }

    @GetMapping("/backlog")
    public ApiResponse<BacklogResponse> getBacklog(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.ok("Backlog retrieved successfully",
                sprintService.getBacklog(projectId, CurrentActorResolver.userId(authentication)));
    }

    @GetMapping("/board")
    public ApiResponse<BoardResponse> getBoard(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ApiResponse.ok("Board retrieved successfully",
                sprintService.getBoard(projectId, CurrentActorResolver.userId(authentication)));
    }

    private UUID parseOptionalUuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), field + " must be a valid UUID");
        }
    }
}
