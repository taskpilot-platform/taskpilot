package com.taskpilot.projects.tasks.controller;

import com.taskpilot.contracts.task.RecoverLegacyReviewCommand;
import com.taskpilot.contracts.task.ReopenTaskCommand;
import com.taskpilot.contracts.task.ReviewTaskCommand;
import com.taskpilot.contracts.task.SubmitTaskCommand;
import com.taskpilot.contracts.task.SupersedeEvaluationCommand;
import com.taskpilot.contracts.task.TaskQualityRubric;
import com.taskpilot.contracts.task.TaskWorkflowResult;
import com.taskpilot.contracts.task.WithdrawTaskCommand;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.tasks.dto.RecoverLegacyReviewWorkflowRequest;
import com.taskpilot.projects.tasks.dto.ReopenTaskWorkflowRequest;
import com.taskpilot.projects.tasks.dto.ReviewTaskWorkflowRequest;
import com.taskpilot.projects.tasks.dto.SubmitTaskWorkflowRequest;
import com.taskpilot.projects.tasks.dto.SupersedeEvaluationWorkflowRequest;
import com.taskpilot.projects.tasks.dto.TaskWorkflowHistoryDto;
import com.taskpilot.projects.tasks.dto.WithdrawTaskWorkflowRequest;
import com.taskpilot.projects.tasks.service.TaskWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskWorkflowController {

    private final TaskWorkflowService taskWorkflowService;

    @GetMapping("/workflow/rubric")
    public ApiResponse<List<TaskQualityRubric.LevelAnchor>> getRubric() {
        return ApiResponse.ok("Task quality rubric retrieved successfully", TaskQualityRubric.QUALITY_V1_ANCHORS);
    }

    @GetMapping("/{taskId}/workflow")
    public ApiResponse<TaskWorkflowHistoryDto> getTaskWorkflow(
            @PathVariable Long taskId,
            Authentication authentication) {
        return ApiResponse.ok("Task workflow history retrieved successfully",
                taskWorkflowService.getWorkflowHistory(taskId, CurrentActorResolver.userId(authentication)));
    }

    @PostMapping("/{taskId}/workflow/submit")
    public ApiResponse<TaskWorkflowResult> submitTask(
            @PathVariable Long taskId,
            @RequestBody(required = false) SubmitTaskWorkflowRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body is required");
        }
        Long version = request.expectedTaskVersion() != null ? request.expectedTaskVersion() : parseIfMatchVersion(ifMatch);
        UUID requestId = request.requestId() != null ? request.requestId() : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        TaskWorkflowResult result = taskWorkflowService.submit(
                new SubmitTaskCommand(taskId, version, requestId, request.summary(), request.evidenceProjectFileIds()),
                CurrentActorResolver.resolve(authentication));
        return ApiResponse.ok("Task submitted for review", result);
    }

    @PostMapping("/{taskId}/workflow/withdraw")
    public ApiResponse<TaskWorkflowResult> withdrawSubmission(
            @PathVariable Long taskId,
            @RequestBody(required = false) WithdrawTaskWorkflowRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body is required");
        }
        Long version = request.expectedTaskVersion() != null ? request.expectedTaskVersion() : parseIfMatchVersion(ifMatch);
        UUID requestId = request.requestId() != null ? request.requestId() : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        TaskWorkflowResult result = taskWorkflowService.withdrawSubmission(
                new WithdrawTaskCommand(taskId, request.submissionId(), version, requestId, request.reason()),
                CurrentActorResolver.resolve(authentication));
        return ApiResponse.ok("Submission withdrawn", result);
    }

    @PostMapping("/{taskId}/workflow/review")
    public ApiResponse<TaskWorkflowResult> reviewSubmission(
            @PathVariable Long taskId,
            @RequestBody(required = false) ReviewTaskWorkflowRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body is required");
        }
        Long version = request.expectedTaskVersion() != null ? request.expectedTaskVersion() : parseIfMatchVersion(ifMatch);
        UUID requestId = request.requestId() != null ? request.requestId() : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        TaskWorkflowResult result = taskWorkflowService.review(
                new ReviewTaskCommand(taskId, request.submissionId(), version, requestId,
                        request.decision(), request.qualityRating(), request.feedback(), request.evidenceSummary()),
                CurrentActorResolver.resolve(authentication));
        return ApiResponse.ok("Task review recorded", result);
    }

    @PostMapping("/{taskId}/workflow/reopen")
    public ApiResponse<TaskWorkflowResult> reopenTask(
            @PathVariable Long taskId,
            @RequestBody(required = false) ReopenTaskWorkflowRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body is required");
        }
        Long version = request.expectedTaskVersion() != null ? request.expectedTaskVersion() : parseIfMatchVersion(ifMatch);
        UUID requestId = request.requestId() != null ? request.requestId() : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        TaskWorkflowResult result = taskWorkflowService.reopen(
                new ReopenTaskCommand(taskId, version, requestId, request.reason()),
                CurrentActorResolver.resolve(authentication));
        return ApiResponse.ok("Task reopened", result);
    }

    @PostMapping("/{taskId}/workflow/supersede")
    public ApiResponse<TaskWorkflowResult> supersedeEvaluation(
            @PathVariable Long taskId,
            @RequestBody(required = false) SupersedeEvaluationWorkflowRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body is required");
        }
        Long version = request.expectedTaskVersion() != null ? request.expectedTaskVersion() : parseIfMatchVersion(ifMatch);
        UUID requestId = request.requestId() != null ? request.requestId() : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        TaskWorkflowResult result = taskWorkflowService.supersedeEvaluation(
                new SupersedeEvaluationCommand(
                        taskId, request.submissionId(), request.expectedEvaluationId(),
                        request.expectedEvaluationVersion(), version, requestId,
                        request.qualityRating(), request.reason(), request.evidenceSummary()),
                CurrentActorResolver.resolve(authentication));
        return ApiResponse.ok("Evaluation superseded", result);
    }

    @PostMapping("/{taskId}/workflow/recover-legacy-review")
    public ApiResponse<TaskWorkflowResult> recoverLegacyReview(
            @PathVariable Long taskId,
            @RequestBody(required = false) RecoverLegacyReviewWorkflowRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Request body is required");
        }
        Long version = request.expectedTaskVersion() != null ? request.expectedTaskVersion() : parseIfMatchVersion(ifMatch);
        UUID requestId = request.requestId() != null ? request.requestId() : parseOptionalUuid(idempotencyKey, "Idempotency-Key");
        TaskWorkflowResult result = taskWorkflowService.recoverLegacyReview(
                new RecoverLegacyReviewCommand(taskId, version, requestId, request.reason()),
                CurrentActorResolver.resolve(authentication));
        return ApiResponse.ok("Legacy REVIEW task recovered to IN_PROGRESS", result);
    }

    private Long parseIfMatchVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String normalized = ifMatch.trim();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2).trim();
        }
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(normalized);
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invalid If-Match version header");
        }
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
