package com.taskpilot.ai.dto;

public record CandidateFeatureDto(
        Long candidateId,
        double fit,
        long load,
        double performanceInput,
        String performanceStatus,
        boolean recommended,
        boolean selected
) {
}
