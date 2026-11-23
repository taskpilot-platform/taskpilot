package com.taskpilot.projects.projects.controller;

import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.projects.dto.ProjectAttentionResponse;
import com.taskpilot.projects.projects.dto.TransferProjectManagerRequest;
import com.taskpilot.projects.projects.service.AdminProjectRecoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "06b. Admin - Project Recovery", description = "Explicit audited recovery for managerless projects")
@RestController
@RequestMapping("/api/v1/admin/projects")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminProjectRecoveryController {

    private final AdminProjectRecoveryService recoveryService;

    @Operation(summary = "List projects needing manager attention")
    @GetMapping("/attention-required")
    public ApiResponse<List<ProjectAttentionResponse>> listProjectsNeedingAttention() {
        return ApiResponse.ok("Projects needing manager attention retrieved",
                recoveryService.listProjectsNeedingAttention());
    }

    @Operation(summary = "Explicitly transfer project management to an active member")
    @PostMapping("/{projectId}/transfer-manager")
    public ApiResponse<Void> transferManager(
            @PathVariable Long projectId,
            @Valid @RequestBody TransferProjectManagerRequest request,
            Authentication authentication) {
        long adminUserId = CurrentActorResolver.userId(authentication);
        recoveryService.transferProjectManager(projectId, request.targetUserId(), adminUserId, request.reason());
        return ApiResponse.ok("Project manager transferred successfully", null);
    }
}
