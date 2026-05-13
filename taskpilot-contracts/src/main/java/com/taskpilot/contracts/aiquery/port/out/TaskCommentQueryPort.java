package com.taskpilot.contracts.aiquery.port.out;

import java.util.List;

import com.taskpilot.contracts.aiquery.dto.TaskCommentSummaryDto;

public interface TaskCommentQueryPort {
    List<TaskCommentSummaryDto> getTaskComments(Long taskId, Long requesterUserId);
}
