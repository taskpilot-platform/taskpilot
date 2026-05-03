package com.taskpilot.ai.adapter.fake;

import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("ai-dev")
public class FakeTaskCommandAdapter implements TaskCommandPort {

    @Override
    public TaskDetailDto getTaskDetails(Long taskId) {
        log.info("[FakeAdapter] getTaskDetails called for taskId={}", taskId);
        return ScenarioFixtures.getTaskDetails(taskId);
    }

    @Override
    public TaskAssignmentResultDto assignTaskToMember(Long taskId, Long memberId, String reason, boolean simulate) {
        log.info("[FakeAdapter] assignTaskToMember called. taskId={}, memberId={}, simulate={}", taskId, memberId, simulate);
        if (simulate) {
            log.info("[FakeAdapter] Simulation mode: Task assignment would succeed.");
            return TaskAssignmentResultDto.success(taskId, memberId, "[SIMULATED] " + reason);
        }
        return TaskAssignmentResultDto.success(taskId, memberId, reason);
    }
}
