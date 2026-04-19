package com.taskpilot.ai.dto;
import lombok.Builder;
import java.util.List;
@Builder
public record AutoAssignmentResponse(
    Long projectId,
    List<String> requiredSkills,
    List<CandidateScore> candidates,
    String aiExplanation
) {}
