package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;

import java.util.List;

public interface TaskCommandPort {
    List<TaskSummaryDto> getTasksByProject(Long projectId, Long requesterUserId);
    List<TaskSummaryDto> getSubtasks(Long parentTaskId, Long requesterUserId);
    TaskDetailDto getTaskDetails(Long taskId, Long requesterUserId);
    TaskAssignmentResultDto assignTaskToMember(Long taskId, Long memberId, String reason, Long requesterUserId,
            boolean simulate);
    TaskSummaryDto updateTaskStatus(Long taskId, String status, Long requesterUserId);
    TaskSummaryDto createTask(Long projectId, String title, String description, String priority, Long parentTaskId,
            Long sprintId, Integer difficultyLevel, Long assigneeId, String dueDate, Long requesterUserId);
}
