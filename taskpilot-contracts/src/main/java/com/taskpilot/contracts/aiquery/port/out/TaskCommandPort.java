package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.TaskAssignmentResultDto;
import com.taskpilot.contracts.aiquery.dto.TaskDetailDto;

public interface TaskCommandPort {
    TaskDetailDto getTaskDetails(Long taskId);
    TaskAssignmentResultDto assignTaskToMember(Long taskId, Long memberId, String reason, boolean simulate);
    // Future commands could be added here
}
