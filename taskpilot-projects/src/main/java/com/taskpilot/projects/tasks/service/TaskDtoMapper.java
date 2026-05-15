package com.taskpilot.projects.tasks.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.taskpilot.projects.common.entity.LabelEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.entity.TaskLabelEntity;
import com.taskpilot.projects.common.repository.LabelRepository;
import com.taskpilot.projects.common.repository.TaskLabelRepository;
import com.taskpilot.projects.tasks.dto.LabelDto;
import com.taskpilot.projects.tasks.dto.TaskDto;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TaskDtoMapper {

    private final LabelRepository labelRepository;
    private final TaskLabelRepository taskLabelRepository;

    public List<TaskDto> mapToDtoWithLabels(List<TaskEntity> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }

        List<Long> taskIds = tasks.stream().map(TaskEntity::getId).toList();
        List<TaskLabelEntity> taskLabels = taskLabelRepository.findByTaskIdIn(taskIds);

        if (taskLabels.isEmpty()) {
            return tasks.stream().map(t -> TaskDto.fromEntity(t, List.of())).collect(Collectors.toList());
        }

        List<Long> labelIds = taskLabels.stream().map(TaskLabelEntity::getLabelId).distinct().toList();
        Map<Long, LabelDto> labelsMap = labelRepository.findAllById(labelIds).stream()
                .collect(Collectors.toMap(LabelEntity::getId,
                        label -> new LabelDto(label.getId(), label.getName(), label.getColor())));

        Map<Long, List<LabelDto>> labelsByTask = taskLabels.stream()
                .filter(taskLabel -> labelsMap.containsKey(taskLabel.getLabelId()))
                .collect(Collectors.groupingBy(
                        TaskLabelEntity::getTaskId,
                        Collectors.mapping(taskLabel -> labelsMap.get(taskLabel.getLabelId()), Collectors.toList())));

        return tasks.stream()
                .map(task -> TaskDto.fromEntity(task, labelsByTask.getOrDefault(task.getId(), List.of())))
                .collect(Collectors.toList());
    }

    public TaskDto mapToDtoWithLabels(TaskEntity task) {
        return mapToDtoWithLabels(List.of(task)).get(0);
    }
}
