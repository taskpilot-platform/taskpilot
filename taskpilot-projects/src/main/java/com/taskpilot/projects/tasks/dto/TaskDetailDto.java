package com.taskpilot.projects.tasks.dto;

import java.util.List;

import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.projects.common.entity.TaskEntity;

import lombok.Builder;

import com.taskpilot.contracts.skill.dto.SkillDto;

@Builder
public record TaskDetailDto(
        TaskDto task,
        UserProfileDto assignee,
        UserProfileDto reporter,
        List<TaskDto> subtasks,
        List<SkillDto> requiredSkills) {
}
