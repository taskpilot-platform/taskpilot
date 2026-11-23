package com.taskpilot.projects.tasks.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.taskpilot.contracts.task.TimelinessStatus;
import com.taskpilot.projects.common.entity.LabelEntity;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.entity.TaskEvaluationEntity;
import com.taskpilot.projects.common.entity.TaskLabelEntity;
import com.taskpilot.projects.common.entity.TaskSubmissionEntity;
import com.taskpilot.projects.common.repository.LabelRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.TaskEvaluationRepository;
import com.taskpilot.projects.common.repository.TaskLabelRepository;
import com.taskpilot.projects.common.repository.TaskSubmissionRepository;
import com.taskpilot.projects.tasks.dto.LabelDto;
import com.taskpilot.projects.tasks.dto.TaskDto;

@Component
public class TaskDtoMapper {

    private final LabelRepository labelRepository;
    private final TaskLabelRepository taskLabelRepository;
    private final ProjectRepository projectRepository;
    private final TaskSubmissionRepository taskSubmissionRepository;
    private final TaskEvaluationRepository taskEvaluationRepository;

    @Autowired
    public TaskDtoMapper(
            LabelRepository labelRepository,
            TaskLabelRepository taskLabelRepository,
            ProjectRepository projectRepository,
            TaskSubmissionRepository taskSubmissionRepository,
            TaskEvaluationRepository taskEvaluationRepository) {
        this.labelRepository = labelRepository;
        this.taskLabelRepository = taskLabelRepository;
        this.projectRepository = projectRepository;
        this.taskSubmissionRepository = taskSubmissionRepository;
        this.taskEvaluationRepository = taskEvaluationRepository;
    }

    public TaskDtoMapper(
            LabelRepository labelRepository,
            TaskLabelRepository taskLabelRepository,
            ProjectRepository projectRepository) {
        this(labelRepository, taskLabelRepository, projectRepository, null, null);
    }

    public TaskDtoMapper(
            LabelRepository labelRepository,
            TaskLabelRepository taskLabelRepository) {
        this(labelRepository, taskLabelRepository, null, null, null);
    }

    public List<TaskDto> mapToDtoWithLabels(List<TaskEntity> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }

        Map<Long, String> timezoneByProject = resolveProjectTimezones(tasks);
        Map<Long, TaskSubmissionEntity> submissionsById = resolveSubmissions(tasks);
        Map<Long, TaskEvaluationEntity> evaluationsById = resolveEvaluations(tasks);
        Map<Long, List<LabelDto>> labelsByTask = resolveLabelsByTask(tasks);

        return tasks.stream()
                .map(task -> {
                    TaskSubmissionEntity sub = task.getCurrentSubmissionId() != null
                            ? submissionsById.get(task.getCurrentSubmissionId())
                            : null;
                    TaskEvaluationEntity eval = task.getCurrentEvaluationId() != null
                            ? evaluationsById.get(task.getCurrentEvaluationId())
                            : null;
                    Integer rating = eval != null ? eval.getQualityRating() : null;
                    Instant submittedAt = eval != null
                            ? eval.getAcceptedSubmittedAt()
                            : (sub != null ? sub.getSubmittedAt() : null);
                    Instant reviewedAt = eval != null ? eval.getReviewedAt() : null;
                    Boolean onTime = eval != null ? eval.getOnTime() : null;
                    TimelinessStatus timelinessStatus = eval != null ? eval.getTimelinessStatus() : null;

                    return TaskDto.fromEntity(
                            task,
                            labelsByTask.getOrDefault(task.getId(), List.of()),
                            timezoneByProject.get(task.getProjectId()),
                            rating,
                            submittedAt,
                            reviewedAt,
                            onTime,
                            timelinessStatus);
                })
                .collect(Collectors.toList());
    }

    public TaskDto mapToDtoWithLabels(TaskEntity task) {
        return mapToDtoWithLabels(List.of(task)).get(0);
    }

    private Map<Long, List<LabelDto>> resolveLabelsByTask(List<TaskEntity> tasks) {
        List<Long> taskIds = tasks.stream().map(TaskEntity::getId).toList();
        List<TaskLabelEntity> taskLabels = taskLabelRepository.findByTaskIdIn(taskIds);
        if (taskLabels.isEmpty()) {
            return Map.of();
        }
        List<Long> labelIds = taskLabels.stream().map(TaskLabelEntity::getLabelId).distinct().toList();
        Map<Long, LabelDto> labelsMap = labelRepository.findAllById(labelIds).stream()
                .collect(Collectors.toMap(LabelEntity::getId,
                        label -> new LabelDto(label.getId(), label.getName(), label.getColor())));
        return taskLabels.stream()
                .filter(taskLabel -> labelsMap.containsKey(taskLabel.getLabelId()))
                .collect(Collectors.groupingBy(
                        TaskLabelEntity::getTaskId,
                        Collectors.mapping(taskLabel -> labelsMap.get(taskLabel.getLabelId()), Collectors.toList())));
    }

    private Map<Long, TaskSubmissionEntity> resolveSubmissions(List<TaskEntity> tasks) {
        if (taskSubmissionRepository == null) {
            return Map.of();
        }
        Set<Long> subIds = tasks.stream()
                .map(TaskEntity::getCurrentSubmissionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (subIds.isEmpty()) {
            return Map.of();
        }
        return taskSubmissionRepository.findByIdIn(subIds).stream()
                .collect(Collectors.toMap(TaskSubmissionEntity::getId, s -> s));
    }

    private Map<Long, TaskEvaluationEntity> resolveEvaluations(List<TaskEntity> tasks) {
        if (taskEvaluationRepository == null) {
            return Map.of();
        }
        Set<Long> evalIds = tasks.stream()
                .map(TaskEntity::getCurrentEvaluationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (evalIds.isEmpty()) {
            return Map.of();
        }
        return taskEvaluationRepository.findByIdIn(evalIds).stream()
                .collect(Collectors.toMap(TaskEvaluationEntity::getId, e -> e));
    }

    private Map<Long, String> resolveProjectTimezones(List<TaskEntity> tasks) {
        if (projectRepository == null) {
            return Map.of();
        }
        Set<Long> projectIds = tasks.stream()
                .map(TaskEntity::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return projectRepository.findAllById(projectIds).stream()
                .collect(Collectors.toMap(ProjectEntity::getId, ProjectEntity::getTimezoneId));
    }
}
