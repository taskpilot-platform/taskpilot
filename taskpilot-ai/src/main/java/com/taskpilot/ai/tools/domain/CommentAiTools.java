package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.aiquery.port.out.TaskCommentQueryPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import com.taskpilot.contracts.aiquery.dto.TaskCommentSummaryDto;
import jakarta.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

import static com.taskpilot.ai.tools.support.AiToolSupport.*;

@Slf4j
@Component
public class CommentAiTools {

    private final TaskCommentQueryPort taskCommentQueryPort;
    private final PendingAiActionService pendingAiActionService;
    @Nullable
    private final JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public CommentAiTools(
            TaskCommentQueryPort taskCommentQueryPort,
            PendingAiActionService pendingAiActionService,
            @Nullable JdbcTemplate jdbcTemplate) {
        this.taskCommentQueryPort = taskCommentQueryPort;
        this.pendingAiActionService = pendingAiActionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public CommentAiTools(
            TaskCommentQueryPort taskCommentQueryPort,
            PendingAiActionService pendingAiActionService) {
        this(taskCommentQueryPort, pendingAiActionService, null);
    }

    @Tool("Fetch comments made on a specific task by task ID.")
    public String getTaskComments(
            @P("The ID of the task") Long taskId,
            @P("Optional. Maximum number of comments to return. Default 10, max 30.") Integer limit) {
        log.info("[AiTool] getTaskComments called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        List<com.taskpilot.contracts.aiquery.dto.TaskCommentSummaryDto> comments = taskCommentQueryPort.getTaskComments(taskId, userId);
        List<com.taskpilot.contracts.aiquery.dto.TaskCommentSummaryDto> limited = comments.stream()
                .limit(limit != null ? Math.max(1, Math.min(limit, 30)) : 10)
                .collect(Collectors.toList());
        try {
            return PATCH_OBJECT_MAPPER.writeValueAsString(limited);
        } catch (Exception e) {
            log.error("[AiTool] Failed to serialize comments", e);
            return "[]";
        }
    }


    @Tool("Fetch comments authored by or mentioning the current user, across projects/tasks. Set mentionedMe=true for mentions.")
    public String getMyTaskComments(
            @P("Optional project ID filter") Long projectId,
            @P("Optional task ID filter") Long taskId,
            @P("True when the user asks for comments mentioning them") Boolean mentionedMe,
            @P("Maximum number of comments to return, between 1 and 50") Integer limit) {
        Long userId = ToolExecutionContext.requireUserId();
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        boolean onlyMentioned = Boolean.TRUE.equals(mentionedMe);
        Long resolvedProjectId = (projectId != null && projectId > 0) ? projectId : null;
        Long resolvedTaskId = (taskId != null && taskId > 0) ? taskId : null;
        log.info("[AiTool] getMyTaskComments called for user {} project={} task={} mentionedMe={} limit={}",
                userId, resolvedProjectId, resolvedTaskId, onlyMentioned, safeLimit);
        try {
            return PATCH_OBJECT_MAPPER.writeValueAsString(
                taskCommentQueryPort.getMyTaskComments(resolvedProjectId, resolvedTaskId, onlyMentioned, safeLimit, userId)
            );
        } catch (Exception e) {
            log.error("[AiTool] Failed to serialize comments", e);
            return "[]";
        }
    }


    @Tool("Create a comment or reply to a comment on a task. Requires confirmation.")
    public Object createTaskComment(
            @P("The ID of the task") Long taskId,
            @P("Comment content") String content,
            @P("Optional parent comment ID when replying") Long parentCommentId,
            @P("Optional mentioned user IDs") List<Long> mentionedUserIds) {
        log.info("[AiTool] createTaskComment called for task {}", taskId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        Long resolvedParentCommentId = (parentCommentId != null && parentCommentId > 0) ? parentCommentId : null;
        return pendingAiActionService.create(
                userId,
                sessionId,
                "createTaskComment",
                "Create comment on task " + taskId,
                args("taskId", taskId, "content", content, "parentCommentId", resolvedParentCommentId,
                        "mentionedUserIds", mentionedUserIds),
                null,
                () -> taskCommentQueryPort.createTaskComment(taskId, content, resolvedParentCommentId, mentionedUserIds,
                        userId));
    }


    @Tool("Update the content of an existing task comment authored by the current user. Requires confirmation.")
    public Object updateTaskComment(
            @P("The ID of the task") Long taskId,
            @P("The ID of the comment") Long commentId,
            @P("Updated comment content") String content,
            @P("Optional mentioned user IDs") List<Long> mentionedUserIds) {
        log.info("[AiTool] updateTaskComment called for task {} comment {}", taskId, commentId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "updateTaskComment",
                "Update comment " + commentId + " on task " + taskId,
                args("taskId", taskId, "commentId", commentId, "content", content,
                        "mentionedUserIds", mentionedUserIds),
                null,
                () -> taskCommentQueryPort.updateTaskComment(taskId, commentId, content, mentionedUserIds,
                        userId));
    }


    @Tool("Partially update a task comment. Send patchData map containing changed fields (content, mentionedUserIds). Requires confirmation.")
    public Object patchTaskComment(
            @Nullable @P("Optional. The ID of the task. If not provided, the system will resolve it automatically.") Long taskId,
            @P("The ID of the comment to patch") Long commentId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        Map<String, Object> patch = normalizePatch(patchData);
        log.info("[AiTool] patchTaskComment called for task {} comment {} with patch {}",
                taskId, commentId, patch);
        patch.keySet().forEach(fieldName -> validatePatchField(fieldName, Set.of("content", "mentionedUserIds")));
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        if (taskId == null && jdbcTemplate != null) {
            try {
                taskId = jdbcTemplate.queryForObject(
                        "select task_id from comments where id = ?",
                        Long.class,
                        commentId
                );
                log.info("[AiTool] Resolved taskId {} for commentId {} from database", taskId, commentId);
            } catch (Exception e) {
                log.warn("[AiTool] Failed to resolve taskId for commentId {} via JDBC: {}", commentId, e.getMessage());
            }
        }
        if (taskId == null) {
            throw new IllegalArgumentException("taskId is required or could not be resolved for commentId: " + commentId);
        }

        final Long resolvedTaskId = taskId;
        TaskCommentSummaryDto existing = taskCommentQueryPort.getTaskComments(resolvedTaskId, userId).stream()
                .filter(comment -> commentId.equals(comment.id()))
                .findFirst()
                .orElse(null);
        String content = patch.containsKey("content")
                ? stringPatchValue(patch, "content")
                : existing != null ? existing.content() : null;
        List<Long> mentionedUserIds = patch.containsKey("mentionedUserIds")
                ? longListPatchValue(patch, "mentionedUserIds")
                : existing != null ? existing.mentionedUserIds() : null;
        if (!hasText(content)) {
            throw new IllegalArgumentException("patchTaskComment requires content, or the existing comment must be readable.");
        }

        return pendingAiActionService.create(
                userId,
                sessionId,
                "patchTaskComment",
                "Patch comment " + commentId + " on task " + resolvedTaskId,
                args("taskId", resolvedTaskId, "commentId", commentId, "patch", patch, "reason", reason),
                args("taskId", resolvedTaskId, "commentId", commentId, "patch", patch, "reason", reason),
                () -> taskCommentQueryPort.updateTaskComment(resolvedTaskId, commentId, content, mentionedUserIds,
                        userId));
    }


    @Tool("Delete a comment from a task. Requires confirmation.")
    public Object deleteTaskComment(
            @Nullable @P("Optional. The ID of the task. If not provided, the system will resolve it automatically.") Long taskId,
            @P("The ID of the comment") Long commentId) {
        log.info("[AiTool] deleteTaskComment called for task {} comment {}", taskId, commentId);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();

        if (taskId == null && jdbcTemplate != null) {
            try {
                taskId = jdbcTemplate.queryForObject(
                        "select task_id from comments where id = ?",
                        Long.class,
                        commentId
                );
                log.info("[AiTool] Resolved taskId {} for commentId {} from database", taskId, commentId);
            } catch (Exception e) {
                log.warn("[AiTool] Failed to resolve taskId for commentId {} via JDBC: {}", commentId, e.getMessage());
            }
        }
        if (taskId == null) {
            throw new IllegalArgumentException("taskId is required or could not be resolved for commentId: " + commentId);
        }

        final Long resolvedTaskId = taskId;
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteTaskComment",
                "Delete comment " + commentId + " on task " + resolvedTaskId,
                args("taskId", resolvedTaskId, "commentId", commentId),
                null,
                () -> taskCommentQueryPort.deleteTaskComment(resolvedTaskId, commentId, userId));
    }


}
