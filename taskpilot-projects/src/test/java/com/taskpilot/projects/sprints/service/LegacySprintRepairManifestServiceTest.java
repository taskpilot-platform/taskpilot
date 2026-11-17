package com.taskpilot.projects.sprints.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.SprintStatus;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.sprints.dto.LegacyRepairDryRunManifestDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

class LegacySprintRepairManifestServiceTest {

    @Test
    void generateProjectDryRunManifest_detectsAllLegacyAnomaliesWithZeroWrites() {
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        SprintRepository sprintRepo = mock(SprintRepository.class);
        TaskRepository taskRepo = mock(TaskRepository.class);
        ProjectSecurityService securityService = mock(ProjectSecurityService.class);

        ProjectEntity project = ProjectEntity.builder().id(10L).name("Legacy Project").build();
        when(securityService.requireProject(10L)).thenReturn(project);
        when(projectRepo.findById(10L)).thenReturn(Optional.of(project));

        SprintEntity active1 = SprintEntity.builder().id(101L).projectId(10L).name("S1").status(SprintStatus.ACTIVE).build();
        SprintEntity active2 = SprintEntity.builder().id(102L).projectId(10L).name("S2").status(SprintStatus.ACTIVE).build();
        SprintEntity completed = SprintEntity.builder().id(103L).projectId(10L).name("S0 Closed").status(SprintStatus.COMPLETED).build();

        when(sprintRepo.findByProjectIdAndStatusOrderByIdAsc(10L, SprintStatus.ACTIVE))
                .thenReturn(List.of(active1, active2));
        when(sprintRepo.findAllById(Set.of(103L))).thenReturn(List.of(completed));

        TaskEntity trapped = TaskEntity.builder()
                .id(501L).projectId(10L).sprintId(103L).status(TaskStatus.IN_PROGRESS).assigneeId(77L)
                .build();
        TaskEntity legacyReview = TaskEntity.builder()
                .id(502L).projectId(10L).sprintId(101L).status(TaskStatus.REVIEW).currentSubmissionId(null)
                .build();

        when(taskRepo.findTrappedUnfinishedInCompletedSprints(10L)).thenReturn(List.of(trapped));
        when(taskRepo.findLegacyReviewMissingSubmission(10L)).thenReturn(List.of(legacyReview));
        when(taskRepo.findLegacyUnverifiedDoneIds(10L)).thenReturn(List.of(503L, 504L));

        LegacySprintRepairManifestService service = new LegacySprintRepairManifestService(
                projectRepo, sprintRepo, taskRepo, securityService);

        LegacyRepairDryRunManifestDto manifest = service.generateProjectDryRunManifest(10L, 1L);

        assertThat(manifest.dryRunOnly()).isTrue();
        assertThat(manifest.mutationsApplied()).isFalse();
        assertThat(manifest.requiresOwnerAuthorizedLiveRepair()).isTrue();
        assertThat(manifest.duplicateActiveSprintProjects()).hasSize(1);
        assertThat(manifest.duplicateActiveSprintProjects().get(0).activeSprintIds()).containsExactly(101L, 102L);
        assertThat(manifest.trappedUnfinishedTasks()).hasSize(1);
        assertThat(manifest.trappedUnfinishedTasks().get(0).taskId()).isEqualTo(501L);
        assertThat(manifest.trappedUnfinishedTasks().get(0).sprintName()).isEqualTo("S0 Closed");
        assertThat(manifest.legacyReviewMissingSubmissions()).hasSize(1);
        assertThat(manifest.legacyReviewMissingSubmissions().get(0).taskId()).isEqualTo(502L);
        assertThat(manifest.legacyDonePreservedIds()).containsExactly(503L, 504L);

        verify(sprintRepo, never()).save(any());
        verify(sprintRepo, never()).saveAll(any());
        verify(taskRepo, never()).save(any());
        verify(taskRepo, never()).saveAll(any());
    }
}
