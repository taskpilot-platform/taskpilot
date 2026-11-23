package com.taskpilot.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

public record AutoAssignmentRequest(
        Long projectId,
        List<String> requiredSkills,
        @Min(1) @Max(10) Integer taskDifficulty,
        String taskTitle,
        Long taskId
) {
    public AutoAssignmentRequest {
        if (taskDifficulty == null) {
            taskDifficulty = 5;
        }
    }

    public AutoAssignmentRequest(
            Long projectId,
            List<String> requiredSkills,
            Integer taskDifficulty,
            String taskTitle
    ) {
        this(projectId, requiredSkills, taskDifficulty, taskTitle, null);
    }
}
