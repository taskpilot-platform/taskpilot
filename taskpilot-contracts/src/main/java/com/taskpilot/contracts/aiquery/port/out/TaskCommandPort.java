package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentContextDto;
import com.taskpilot.contracts.task.PatchTaskCommand;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskCommandPort {
        List<TaskSummaryDto> getTasksByProject(Long projectId, Long requesterUserId);

        List<TaskDetailDto> getUnassignedTasksByProject(Long projectId, Long requesterUserId);

        List<TaskSummaryDto> getSubtasks(Long parentTaskId, Long requesterUserId);

        TaskDetailDto getTaskDetails(Long taskId, Long requesterUserId);

        TaskSummaryDto patchTask(PatchTaskCommand command, Long requesterUserId);

        TaskSummaryDto updateTaskRequiredSkills(Long taskId, String skills, Long expectedTaskVersion, UUID requestId,
                        Long requesterUserId);

        TaskAssignmentResultDto assignTaskToMember(Long taskId, Long memberId, String reason, Long expectedTaskVersion,
                        UUID requestId, Long requesterUserId, boolean simulate);

        default TaskAssignmentResultDto assignTaskToMember(Long taskId, Long memberId, String reason,
                        Long expectedTaskVersion, UUID requestId, Long requesterUserId, boolean simulate,
                        String assignmentDecisionId) {
                return assignTaskToMember(taskId, memberId, reason, expectedTaskVersion, requestId, requesterUserId,
                                simulate);
        }

        default Optional<TaskAssignmentContextDto> getTaskAssignmentContext(Long taskId, Long requesterUserId) {
                return Optional.empty();
        }

        TaskSummaryDto updateTaskStatus(Long taskId, String status, Long expectedTaskVersion, UUID requestId,
                        Long requesterUserId);

        TaskSummaryDto updateTask(Long taskId, String title, String description, String status, String priority,
                        Float position, List<Long> labelIds, Integer difficultyLevel, List<Long> requiredSkillIds,
                        Long assigneeId, String startDate, String dueDate, Long expectedTaskVersion, UUID requestId,
                        Long requesterUserId);

        TaskSummaryDto createTask(Long projectId, String title, String description, String priority, Float position,
                        Long parentTaskId, Long sprintId, Integer difficultyLevel, List<Long> labelIds,
                        List<Long> requiredSkillIds, Long assigneeId, String startDate, String dueDate, UUID requestId,
                        Long requesterUserId);

        void deleteTask(Long taskId, Long expectedTaskVersion, UUID requestId, Long requesterUserId);

        TaskSummaryDto moveTaskKanban(Long taskId, String status, Float position, Long expectedTaskVersion,
                        UUID requestId, Long requesterUserId);

        default TaskSummaryDto createSuccessorTask(Long sourceTaskId, String title, String description,
                        String priority, Long sprintId, Integer difficultyLevel, Long assigneeId,
                        String startDate, String dueDate, UUID requestId, Long requesterUserId) {
                throw new UnsupportedOperationException("createSuccessorTask is not implemented");
        }
}
