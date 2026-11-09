package com.taskpilot.ai.dto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
public record AutoAssignmentRequest(
    @NotNull(message = "Project ID is required")
    Long projectId,
    List<String> requiredSkills,
    @Min(1) @Max(10) Integer taskDifficulty,
    String taskTitle
) {
    public AutoAssignmentRequest {
        if (taskDifficulty == null) taskDifficulty = 5;
    }
}
