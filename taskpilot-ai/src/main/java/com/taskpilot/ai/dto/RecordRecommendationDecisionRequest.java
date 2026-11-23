package com.taskpilot.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RecordRecommendationDecisionRequest(
        @NotBlank String decisionType,
        Long selectedCandidateId,
        Long taskId,
        Long expectedTaskVersion,
        UUID requestId,
        @Size(max = 64) String reasonCode,
        @Size(max = 1000) String note
) {
}
