package com.taskpilot.projects.chat.controller;

import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.projects.chat.dto.ChatMessageResponse;
import com.taskpilot.projects.chat.dto.SendChatMessageRequest;
import com.taskpilot.projects.chat.service.ProjectChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "16. Project Chat", description = "APIs for project-level real-time team chat")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chat")
@RequiredArgsConstructor
public class ProjectChatController {

    private final ProjectChatService projectChatService;

    @Operation(summary = "Get chat history", description = "Get paginated history of project chat messages")
    @GetMapping("/messages")
    public ApiResponse<Page<ChatMessageResponse>> getChatHistory(
            @PathVariable Long projectId,
            Authentication authentication,
            @PageableDefault(size = 50) Pageable pageable) {
        return ApiResponse.ok("Chat messages retrieved successfully",
                projectChatService.getChatHistory(projectId, authentication.getName(), pageable));
    }

    @Operation(summary = "Send chat message via REST", description = "Send a chat message to the project chat room and broadcast over WebSocket")
    @PostMapping("/messages")
    public ApiResponse<ChatMessageResponse> sendMessage(
            @PathVariable Long projectId,
            Authentication authentication,
            @Valid @RequestBody SendChatMessageRequest request) {
        return ApiResponse.created("Message sent successfully",
                projectChatService.sendMessage(projectId, authentication.getName(), request));
    }
}
