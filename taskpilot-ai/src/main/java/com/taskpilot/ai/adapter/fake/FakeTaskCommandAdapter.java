package com.taskpilot.ai.adapter.fake;

import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.dto.TaskSummaryDto;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Profile("ai-dev")
public class FakeTaskCommandAdapter implements TaskCommandPort {

    @Override
    public List<TaskSummaryDto> getTasksByProject(Long projectId, Long requesterUserId) {
        log.info("[FakeAdapter] getTasksByProject called for projectId={}", projectId);
        return List.of();
    }

    @Override
    public List<TaskSummaryDto> getSubtasks(Long parentTaskId, Long requesterUserId) {
        log.info("[FakeAdapter] getSubtasks called for parentTaskId={}", parentTaskId);
        return List.of();
    }

    @Override
    public TaskDetailDto getTaskDetails(Long taskId, Long requesterUserId) {
        log.info("[FakeAdapter] getTaskDetails called for taskId={}", taskId);
        return ScenarioFixtures.getTaskDetails(taskId);
    }

    @Override
    public TaskAssignmentResultDto assignTaskToMember(Long taskId, Long memberId, String reason, Long requesterUserId,
            boolean simulate) {
        log.info("[FakeAdapter] assignTaskToMember called. taskId={}, memberId={}, simulate={}", taskId, memberId, simulate);
        if (simulate) {
            log.info("[FakeAdapter] Simulation mode: Task assignment would succeed.");
            return TaskAssignmentResultDto.success(taskId, memberId, "[SIMULATED] " + reason);
        }
        return TaskAssignmentResultDto.success(taskId, memberId, reason);
    }

    @Override
    public TaskSummaryDto updateTaskStatus(Long taskId, String status, Long requesterUserId) {
        log.info("[FakeAdapter] updateTaskStatus called for taskId={} status={}", taskId, status);
        return new TaskSummaryDto(taskId, 1L, null, null, "Sample task", null, status, "MEDIUM",
                3, null, null, null, null, null);
    }

    @Override
    public TaskSummaryDto createTask(Long projectId, String title, String description, String priority,
            Long parentTaskId, Long sprintId, Integer difficultyLevel, Long assigneeId, String dueDate,
            Long requesterUserId) {
        log.info("[FakeAdapter] createTask called for projectId={} title={}", projectId, title);
        return new TaskSummaryDto(999L, projectId, parentTaskId, sprintId, title, description, "TODO",
                priority != null ? priority : "MEDIUM", difficultyLevel, assigneeId, null, dueDate, null, null);
    }
}
