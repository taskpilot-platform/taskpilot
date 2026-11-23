package com.taskpilot.projects.sprints.service;

import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.SprintStatus;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.sprints.dto.LegacyRepairDryRunManifestDto;
import com.taskpilot.projects.sprints.dto.LegacyRepairDryRunManifestDto.DuplicateActiveSprintAnomaly;
import com.taskpilot.projects.sprints.dto.LegacyRepairDryRunManifestDto.LegacyReviewAnomaly;
import com.taskpilot.projects.sprints.dto.LegacyRepairDryRunManifestDto.TrappedTaskAnomaly;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates an immutable read-only dry-run repair manifest for legacy sprint anomalies:
 * duplicate ACTIVE sprints, unfinished tasks trapped in COMPLETED sprints, legacy REVIEW
 * tasks missing submission records, and preserved LEGACY_UNVERIFIED DONE tasks.
 * Never mutates database state.
 */
@Service
@RequiredArgsConstructor
public class LegacySprintRepairManifestService {

    private final ProjectRepository projectRepository;
    private final SprintRepository sprintRepository;
    private final TaskRepository taskRepository;
    private final ProjectSecurityService projectSecurityService;
    private final Clock clock = Clock.systemUTC();

    @Transactional(readOnly = true)
    public LegacyRepairDryRunManifestDto generateProjectDryRunManifest(Long projectId, Long actorUserId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateManager(projectId, actorUserId);
        return buildManifest(projectId);
    }

    @Transactional(readOnly = true)
    public LegacyRepairDryRunManifestDto generateGlobalDryRunManifest() {
        return buildManifest(null);
    }

    private LegacyRepairDryRunManifestDto buildManifest(Long projectId) {
        Instant now = clock.instant();

        List<SprintEntity> activeSprints = projectId != null
                ? sprintRepository.findByProjectIdAndStatusOrderByIdAsc(projectId, SprintStatus.ACTIVE)
                : sprintRepository.findByStatusOrderByProjectIdAscIdAsc(SprintStatus.ACTIVE);

        Map<Long, List<SprintEntity>> activeByProject = activeSprints.stream()
                .filter(s -> s.getProjectId() != null)
                .collect(Collectors.groupingBy(SprintEntity::getProjectId, LinkedHashMap::new, Collectors.toList()));

        List<DuplicateActiveSprintAnomaly> duplicateAnomalies = new ArrayList<>();
        for (Map.Entry<Long, List<SprintEntity>> entry : activeByProject.entrySet()) {
            if (entry.getValue().size() > 1) {
                Long pid = entry.getKey();
                String pname = projectRepository.findById(pid).map(ProjectEntity::getName).orElse("Project #" + pid);
                List<Long> sprintIds = entry.getValue().stream().map(SprintEntity::getId).sorted().toList();
                duplicateAnomalies.add(new DuplicateActiveSprintAnomaly(
                        pid,
                        pname,
                        sprintIds,
                        "MULTIPLE_ACTIVE_SPRINTS",
                        "REQUIRES_OWNER_REVIEW_TO_SELECT_ACTIVE_SPRINT"));
            }
        }

        List<TaskEntity> trappedTasks = taskRepository.findTrappedUnfinishedInCompletedSprints(projectId);
        Set<Long> trappedSprintIds = trappedTasks.stream()
                .map(TaskEntity::getSprintId)
                .collect(Collectors.toSet());
        Map<Long, String> sprintNames = trappedSprintIds.isEmpty()
                ? Map.of()
                : sprintRepository.findAllById(trappedSprintIds).stream()
                        .collect(Collectors.toMap(SprintEntity::getId, SprintEntity::getName, (a, b) -> a));

        List<TrappedTaskAnomaly> trappedAnomalies = trappedTasks.stream()
                .map(t -> new TrappedTaskAnomaly(
                        t.getId(),
                        t.getProjectId(),
                        t.getSprintId(),
                        sprintNames.getOrDefault(t.getSprintId(), "Sprint #" + t.getSprintId()),
                        t.getStatus().name(),
                        t.getAssigneeId(),
                        t.getCurrentSubmissionId(),
                        t.getCurrentSubmissionId() != null,
                        "TRAPPED_UNFINISHED_TASK_IN_COMPLETED_SPRINT",
                        "MIGRATION_CARRYOVER_TO_BACKLOG_OR_WRITABLE_SPRINT"))
                .toList();

        List<LegacyReviewAnomaly> legacyReviews = taskRepository.findLegacyReviewMissingSubmission(projectId).stream()
                .map(t -> new LegacyReviewAnomaly(
                        t.getId(),
                        t.getProjectId(),
                        t.getSprintId(),
                        "LEGACY_REVIEW_MISSING_SUBMISSION",
                        "EXPLICIT_RECOVER_LEGACY_REVIEW"))
                .toList();

        List<Long> legacyDoneIds = taskRepository.findLegacyUnverifiedDoneIds(projectId);

        boolean requiresOwnerRepair = !duplicateAnomalies.isEmpty()
                || !trappedAnomalies.isEmpty()
                || !legacyReviews.isEmpty();

        return new LegacyRepairDryRunManifestDto(
                now,
                true,
                false,
                requiresOwnerRepair,
                duplicateAnomalies,
                trappedAnomalies,
                legacyReviews,
                legacyDoneIds);
    }
}
