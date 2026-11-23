package com.taskpilot.ai.controller;

import com.taskpilot.ai.dto.AdaptiveDatasetQualityReportDto;
import com.taskpilot.ai.service.AdaptiveDatasetService;
import com.taskpilot.contracts.task.TaskEvaluationJournalStatePort;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Global Admin REST controller for Adaptive dataset-v2 quality reports and journal reconciliation.
 * Strictly requires global ADMIN authority under canonical /api/v1/admin/ai/adaptive.
 */
@RestController
@RequestMapping("/api/v1/admin/ai/adaptive")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - AI Adaptive", description = "Admin endpoints for Adaptive dataset-v2 quality reports")
public class AdaptiveAdminController {

    private final AdaptiveDatasetService datasetService;
    private final TaskEvaluationJournalStatePort journalStatePort;

    @Operation(summary = "Get Adaptive Dataset-v2 Quality Report",
            description = "Inspect scoped evidence counts, exclusion reasons, and deterministic SHA-256 dataset hash.")
    @GetMapping("/dataset-report")
    public ApiResponse<AdaptiveDatasetQualityReportDto> getDatasetReport(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long managerId,
            @RequestParam(defaultValue = "BALANCED") String mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int limit,
            Authentication authentication) {
        verifyAdminAuthority(authentication);
        AdaptiveDatasetQualityReportDto report = datasetService.generateQualityReport(
                projectId, managerId, mode, page, limit);
        return ApiResponse.ok("Adaptive dataset quality report retrieved successfully", report);
    }

    @Operation(summary = "Replay Due Evaluation Journal Entries",
            description = "Manually trigger replay of due pending/retry-pending evaluation journal entries.")
    @PostMapping("/journal/replay")
    public ApiResponse<Map<String, Object>> replayDueJournalEntries(Authentication authentication) {
        verifyAdminAuthority(authentication);
        int replayed = journalStatePort.replayDueEntries();
        TaskEvaluationJournalStatePort.JournalHealthSummary summary = journalStatePort.summarize(null);
        return ApiResponse.ok("Evaluation journal replay completed", Map.of(
                "replayedCount", replayed,
                "pendingCount", summary.pendingCount() + summary.retryPendingCount(),
                "failedCount", summary.failedCount()));
    }

    private static void verifyAdminAuthority(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
        }
        boolean isAdmin = authentication.getAuthorities() != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Access denied. Admin role required.");
        }
    }
}
