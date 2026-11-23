package com.taskpilot.projects.projects.controller;

import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.projects.dto.AcceptProjectInvitationRequest;
import com.taskpilot.projects.projects.dto.CreateProjectInvitationRequest;
import com.taskpilot.projects.projects.dto.ProjectInvitationCreatedResponse;
import com.taskpilot.projects.projects.dto.ProjectInvitationResponse;
import com.taskpilot.projects.projects.dto.ProjectMemberResponse;
import com.taskpilot.projects.projects.service.ProjectInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "07b. Project Invitations", description = "Private email-bound project invitations")
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectInvitationController {

    private final ProjectInvitationService invitationService;

    @Operation(summary = "Create project invitation", description = "Issue a single-use 7-day private invitation for a verified recipient email.")
    @PostMapping("/{projectId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectInvitationCreatedResponse> createInvitation(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateProjectInvitationRequest request,
            Authentication authentication) {
        long actorUserId = CurrentActorResolver.userId(authentication);
        return ApiResponse.created("Invitation created successfully",
                invitationService.createInvitation(projectId, request.email(), actorUserId));
    }

    @Operation(summary = "List project invitations", description = "List redacted invitations for a project (Manager only).")
    @GetMapping("/{projectId}/invitations")
    public ApiResponse<List<ProjectInvitationResponse>> listInvitations(
            @PathVariable Long projectId,
            Authentication authentication) {
        long actorUserId = CurrentActorResolver.userId(authentication);
        return ApiResponse.ok("Invitations retrieved successfully",
                invitationService.listInvitations(projectId, actorUserId));
    }

    @Operation(summary = "Revoke project invitation", description = "Revoke a pending project invitation (Manager only).")
    @DeleteMapping("/{projectId}/invitations/{invitationId}")
    public ApiResponse<Void> revokeInvitation(
            @PathVariable Long projectId,
            @PathVariable Long invitationId,
            Authentication authentication) {
        long actorUserId = CurrentActorResolver.userId(authentication);
        invitationService.revokeInvitation(projectId, invitationId, actorUserId);
        return ApiResponse.ok("Invitation revoked successfully", null);
    }

    @Operation(summary = "Accept project invitation", description = "Accept a private project invitation token using current verified account.")
    @PostMapping("/invitations/accept")
    public ApiResponse<ProjectMemberResponse> acceptInvitation(
            @Valid @RequestBody AcceptProjectInvitationRequest request,
            Authentication authentication) {
        long actorUserId = CurrentActorResolver.userId(authentication);
        return ApiResponse.ok("Invitation accepted successfully",
                invitationService.acceptInvitation(request.token(), actorUserId));
    }
}
