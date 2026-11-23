package com.taskpilot.projects.sprints.dto;

import com.taskpilot.projects.common.enums.SprintStatus;
import com.taskpilot.projects.common.enums.TaskStatus;

import java.time.LocalDate;
import java.util.List;

public record SprintCompletePreviewResponse(
        Long sprintId,
        String sprintName,
        Long sprintVersion,
        int totalTasks,
        int doneTasksRetained,
        int unfinishedTotal,
        int todoCount,
        int inProgressCount,
        int reviewCount,
        List<Long> unfinishedTaskIds,
        List<UnfinishedTaskItem> unfinishedTasks,
        List<TargetSprintOption> availableTargetSprints
) {
    public record UnfinishedTaskItem(
            Long id,
            String title,
            TaskStatus status,
            Long assigneeId,
            Long version,
            boolean hasPendingSubmission
    ) {
    }

    public record TargetSprintOption(
            Long id,
            String name,
            SprintStatus status,
            Long version,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }
}
