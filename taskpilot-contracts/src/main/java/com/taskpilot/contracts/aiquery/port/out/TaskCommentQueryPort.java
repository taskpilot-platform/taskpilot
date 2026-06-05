package com.taskpilot.contracts.aiquery.port.out;

import java.util.List;

import com.taskpilot.contracts.aiquery.dto.TaskCommentSearchSummaryDto;
import com.taskpilot.contracts.aiquery.dto.TaskCommentSummaryDto;

public interface TaskCommentQueryPort {
    List<TaskCommentSummaryDto> getTaskComments(Long taskId, Long requesterUserId);
    List<TaskCommentSearchSummaryDto> getMyTaskComments(Long projectId, Long taskId, boolean mentionedMe, int limit,
            Long requesterUserId);
    TaskCommentSummaryDto createTaskComment(Long taskId, String content, Long parentCommentId,
            List<Long> mentionedUserIds, Long requesterUserId);
    TaskCommentSummaryDto updateTaskComment(Long taskId, Long commentId, String content,
            List<Long> mentionedUserIds, Long requesterUserId);
    TaskCommentSummaryDto deleteTaskComment(Long taskId, Long commentId, Long requesterUserId);
}
