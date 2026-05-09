package com.taskpilot.projects.tasks.dto;

import java.util.List;

import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.projects.common.entity.TaskEntity;

import lombok.Builder;

@Builder
public record TaskDetailDto(
        TaskDto task,
        UserProfileDto assignee,
        UserProfileDto reporter,
        List<TaskDto> subtasks) {

    public static TaskDetailDto from(TaskEntity taskEntity, UserProfileDto assignee, UserProfileDto reporter,
            List<TaskDto> subtasks) {
        return TaskDetailDto.builder()
                .task(TaskDto.fromEntity(taskEntity))
                .assignee(assignee)
                .reporter(reporter)
                .subtasks(subtasks)
                .build();
    }
}
