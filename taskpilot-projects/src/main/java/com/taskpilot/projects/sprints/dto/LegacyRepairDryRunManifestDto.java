package com.taskpilot.projects.sprints.dto;

import java.time.Instant;
import java.util.List;

public record LegacyRepairDryRunManifestDto(
        Instant generatedAt,
        boolean dryRunOnly,
        boolean mutationsApplied,
        boolean requiresOwnerAuthorizedLiveRepair,
        List<DuplicateActiveSprintAnomaly> duplicateActiveSprintProjects,
        List<TrappedTaskAnomaly> trappedUnfinishedTasks,
        List<LegacyReviewAnomaly> legacyReviewMissingSubmissions,
        List<Long> legacyDonePreservedIds
) {
    public record DuplicateActiveSprintAnomaly(
            Long projectId,
            String projectName,
            List<Long> activeSprintIds,
            String reason,
            String recommendedAction
    ) {
    }

    public record TrappedTaskAnomaly(
            Long taskId,
            Long projectId,
            Long sprintId,
            String sprintName,
            String taskStatus,
            Long assigneeId,
            Long currentSubmissionId,
            boolean hasPendingSubmission,
            String reason,
            String recommendedCorrection
    ) {
    }

    public record LegacyReviewAnomaly(
            Long taskId,
            Long projectId,
            Long sprintId,
            String reason,
            String recommendedCorrection
    ) {
    }
}
