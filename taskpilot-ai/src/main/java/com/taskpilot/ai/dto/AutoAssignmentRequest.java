package com.taskpilot.ai.dto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
public record AutoAssignmentRequest(
    Long projectId,
    List<String> requiredSkills,
    @Min(1) @Max(10) Integer taskDifficulty,
    String taskTitle
) {
    public AutoAssignmentRequest {
        if (taskDifficulty == null) taskDifficulty = 5;
    }
}
