package com.taskpilot.ai.controller;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.infrastructure.config.security.CurrentActorResolver;
import com.taskpilot.infrastructure.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/ai/sessions/{sessionId}/actions") @RequiredArgsConstructor
public class AiActionController {
    private final PendingAiActionService actions;
    public record ConfirmRequest(@NotBlank @Size(max=128) String idempotencyKey,
                                 @NotBlank @Size(max=64) String payloadHash) {}
    @GetMapping("/{actionId}")
    public ApiResponse<ConfirmationRequiredDto> preview(@PathVariable Long sessionId,@PathVariable String actionId,Authentication auth) {
        return ApiResponse.success(actions.describe(actionId,CurrentActorResolver.userId(auth),sessionId));
    }
    @PostMapping("/{actionId}/confirm")
    public ApiResponse<Object> confirm(@PathVariable Long sessionId,@PathVariable String actionId,
            @Valid @RequestBody ConfirmRequest request,Authentication auth) {
        return ApiResponse.success(actions.confirmExplicit(actionId,CurrentActorResolver.resolve(auth),sessionId,
                request.idempotencyKey(),request.payloadHash()));
    }
    @PostMapping("/{actionId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long sessionId,@PathVariable String actionId,Authentication auth) {
        actions.cancel(actionId,CurrentActorResolver.userId(auth),sessionId);
        return ApiResponse.success(null);
    }
}
