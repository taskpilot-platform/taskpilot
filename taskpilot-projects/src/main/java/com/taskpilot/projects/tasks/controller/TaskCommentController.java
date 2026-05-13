package com.taskpilot.projects.tasks.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.tasks.dto.CreateTaskCommentRequest;
import com.taskpilot.projects.tasks.dto.TaskCommentDto;
import com.taskpilot.projects.tasks.dto.UpdateTaskCommentRequest;
import com.taskpilot.projects.tasks.service.TaskCommentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}/comments")
@RequiredArgsConstructor
public class TaskCommentController {

    private final TaskCommentService taskCommentService;

    @GetMapping
    public ApiResponse<List<TaskCommentDto>> getComments(
            @PathVariable Long taskId,
            Authentication authentication) {
        return ApiResponse.success(
                HttpStatus.OK.value(),
                "Task comments retrieved successfully",
                taskCommentService.getComments(taskId, authentication.getName()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskCommentDto> createComment(
            @PathVariable Long taskId,
            @Valid @RequestBody CreateTaskCommentRequest request,
            Authentication authentication) {
        return ApiResponse.success(
                HttpStatus.CREATED.value(),
                "Task comment created successfully",
                taskCommentService.createComment(taskId, request, authentication.getName()));
    }

    @PutMapping("/{commentId}")
    public ApiResponse<TaskCommentDto> updateComment(
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            @Valid @RequestBody UpdateTaskCommentRequest request,
            Authentication authentication) {
        return ApiResponse.success(
                HttpStatus.OK.value(),
                "Task comment updated successfully",
                taskCommentService.updateComment(taskId, commentId, request, authentication.getName()));
    }

    @DeleteMapping("/{commentId}")
    public ApiResponse<Void> deleteComment(
            @PathVariable Long taskId,
            @PathVariable Long commentId,
            Authentication authentication) {
        taskCommentService.deleteComment(taskId, commentId, authentication.getName());
        return ApiResponse.success(HttpStatus.OK.value(), "Task comment deleted successfully", null);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamComments(
            @PathVariable Long taskId,
            Authentication authentication) {
        return taskCommentService.streamComments(taskId, authentication.getName());
    }

    @GetMapping("/mention-candidates")
    public ApiResponse<List<UserProfileLiteDto>> getMentionCandidates(
            @PathVariable Long taskId,
            @RequestParam(required = false) String keyword,
            Authentication authentication) {
        return ApiResponse.success(
                HttpStatus.OK.value(),
                "Mention candidates retrieved successfully",
                taskCommentService.getMentionCandidates(taskId, keyword, authentication.getName()));
    }
}
