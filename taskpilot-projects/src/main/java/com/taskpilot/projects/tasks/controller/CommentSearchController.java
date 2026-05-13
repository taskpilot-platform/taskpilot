package com.taskpilot.projects.tasks.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.tasks.dto.CommentSearchResultDto;
import com.taskpilot.projects.tasks.service.TaskCommentService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/comments")
@RequiredArgsConstructor
public class CommentSearchController {

    private final TaskCommentService taskCommentService;

    @GetMapping
    public ApiResponse<Page<CommentSearchResultDto>> searchComments(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long authorId,
            @RequestParam(defaultValue = "false") boolean mentionedMe,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        return ApiResponse.success(
                HttpStatus.OK.value(),
                "Comments retrieved successfully",
                taskCommentService.searchComments(
                        keyword,
                        projectId,
                        taskId,
                        authorId,
                        mentionedMe,
                        authentication.getName(),
                        pageable));
    }
}
