package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;

import java.util.List;

public interface TaskCommandPort {
    List<TaskSummaryDto> getTasksByProject(Long projectId, Long requesterUserId);
    List<TaskDetailDto> getUnassignedTasksByProject(Long projectId, Long requesterUserId);
    List<TaskSummaryDto> getSubtasks(Long parentTaskId, Long requesterUserId);
    TaskDetailDto getTaskDetails(Long taskId, Long requesterUserId);
    TaskSummaryDto updateTaskRequiredSkills(Long taskId, String skills, Long requesterUserId);
    TaskAssignmentResultDto assignTaskToMember(Long taskId, Long memberId, String reason, Long requesterUserId,
            boolean simulate);
    TaskSummaryDto updateTaskStatus(Long taskId, String status, Long requesterUserId);
    TaskSummaryDto updateTask(Long taskId, String title, String description, String status, String priority,
            Float position, List<Long> labelIds, Integer difficultyLevel, List<Long> requiredSkillIds,
            Long assigneeId, String startDate, String dueDate, Long requesterUserId);
    TaskSummaryDto createTask(Long projectId, String title, String description, String priority, Float position,
            Long parentTaskId, Long sprintId, Integer difficultyLevel, List<Long> labelIds,
            List<Long> requiredSkillIds, Long assigneeId, String startDate, String dueDate, Long requesterUserId);
    void deleteTask(Long taskId, Long requesterUserId);
    TaskSummaryDto moveTaskKanban(Long taskId, String status, Float position, Long requesterUserId);
}
