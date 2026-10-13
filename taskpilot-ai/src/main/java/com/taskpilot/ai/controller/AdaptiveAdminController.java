package com.taskpilot.ai.controller;

import com.taskpilot.ai.dto.AdaptiveAdminRequests.ApproveProposalRequest;
import com.taskpilot.ai.dto.AdaptiveAdminRequests.RejectProposalRequest;
import com.taskpilot.ai.dto.AdaptiveDatasetQualityReportDto;
import com.taskpilot.ai.dto.AdaptiveWeightProposalDto;
import com.taskpilot.ai.service.AdaptiveDatasetService;
import com.taskpilot.ai.service.AdaptiveWeightProposalService;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Global Admin REST controller for managing Phase 2E/2F Adaptive datasets and offline weight proposals.
 * All endpoints strictly require global ADMIN authority.
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/admin/ai/adaptive", "/api/admin/ai/adaptive"})
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - AI Adaptive", description = "Admin endpoints for offline Adaptive datasets and shadow proposals")
public class AdaptiveAdminController {

    private final AdaptiveDatasetService datasetService;
    private final AdaptiveWeightProposalService proposalService;
    private final UserIdentityPort userIdentityPort;

    @Operation(summary = "Get Adaptive Dataset Quality Report", description = "Inspect evidence counts, exclusion breakdown, and canonical SHA-256 hash.")
    @GetMapping("/dataset-report")
    public ApiResponse<AdaptiveDatasetQualityReportDto> getDatasetReport(Authentication authentication) {
        verifyAdminAuthority(authentication);
        AdaptiveDatasetQualityReportDto report = datasetService.generateQualityReport();
        return ApiResponse.ok("Adaptive dataset quality report retrieved successfully", report);
    }

    @Operation(summary = "Generate Offline Weight Proposal", description = "Generate a new shadow proposal using deterministic grid search.")
    @PostMapping("/proposals/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdaptiveWeightProposalDto> generateProposal(Authentication authentication) {
        Long adminUserId = resolveAdminUserId(authentication);
        AdaptiveWeightProposalDto proposal = proposalService.generateProposal(adminUserId);
        return ApiResponse.created("Adaptive weight proposal generated successfully", proposal);
    }

    @Operation(summary = "Get Latest Weight Proposal", description = "Retrieve the latest generated proposal.")
    @GetMapping("/proposals/latest")
    public ApiResponse<AdaptiveWeightProposalDto> getLatestProposal(Authentication authentication) {
        verifyAdminAuthority(authentication);
        AdaptiveWeightProposalDto proposal = proposalService.getLatestProposal();
        return ApiResponse.ok("Latest proposal retrieved successfully", proposal);
    }

    @Operation(summary = "Get Proposal by ID", description = "Retrieve an adaptive weight proposal by proposalId.")
    @GetMapping("/proposals/{proposalId}")
    public ApiResponse<AdaptiveWeightProposalDto> getProposalById(
            @PathVariable String proposalId,
            Authentication authentication) {
        verifyAdminAuthority(authentication);
        AdaptiveWeightProposalDto proposal = proposalService.getProposalById(proposalId);
        return ApiResponse.ok("Proposal retrieved successfully", proposal);
    }

    @Operation(summary = "List All Weight Proposals", description = "List all adaptive weight proposals in descending order.")
    @GetMapping("/proposals")
    public ApiResponse<List<AdaptiveWeightProposalDto>> getAllProposals(Authentication authentication) {
        verifyAdminAuthority(authentication);
        List<AdaptiveWeightProposalDto> proposals = proposalService.getAllProposals();
        return ApiResponse.ok("Proposals retrieved successfully", proposals);
    }

    @Operation(summary = "Approve Weight Proposal", description = "Approve a proposal. Does NOT activate or mutate active production weights.")
    @PostMapping("/proposals/{proposalId}/approve")
    public ApiResponse<AdaptiveWeightProposalDto> approveProposal(
            @PathVariable String proposalId,
            @RequestBody(required = false) ApproveProposalRequest request,
            Authentication authentication) {
        Long adminUserId = resolveAdminUserId(authentication);
        String notes = request != null ? request.notes() : null;
        AdaptiveWeightProposalDto approved = proposalService.approveProposal(proposalId, adminUserId, notes);
        return ApiResponse.ok("Proposal approved successfully. Production weights remain unchanged.", approved);
    }

    @Operation(summary = "Reject Weight Proposal", description = "Reject an adaptive weight proposal.")
    @PostMapping("/proposals/{proposalId}/reject")
    public ApiResponse<AdaptiveWeightProposalDto> rejectProposal(
            @PathVariable String proposalId,
            @RequestBody(required = false) RejectProposalRequest request,
            Authentication authentication) {
        Long adminUserId = resolveAdminUserId(authentication);
        String reason = request != null ? request.reason() : null;
        AdaptiveWeightProposalDto rejected = proposalService.rejectProposal(proposalId, adminUserId, reason);
        return ApiResponse.ok("Proposal rejected successfully", rejected);
    }

    private void verifyAdminAuthority(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        boolean isAdmin = authentication.getAuthorities() != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Access denied. Admin role required.");
        }
    }

    private Long resolveAdminUserId(Authentication authentication) {
        verifyAdminAuthority(authentication);
        return userIdentityPort.findByEmail(authentication.getName())
                .map(identity -> identity.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(),
                        "Authenticated admin user not found: " + authentication.getName()));
    }
}
