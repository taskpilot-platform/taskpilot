package com.taskpilot.ai.dto;

import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record AutoAssignmentResponse(
        Long projectId,
        List<String> requiredSkills,
        List<CandidateScore> candidates,
        String aiExplanation,
        String presentationContractVersion,
        String scoringModelVersion,
        String differentiationStatus,
        String heuristicMode,
        String snapshotId,
        Long taskId,
        Long taskVersion
) {
}
