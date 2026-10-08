package com.taskpilot.projects.chat.controller;

import com.taskpilot.projects.chat.dto.ChatMessageResponse;
import com.taskpilot.projects.chat.dto.SendChatMessageRequest;
import com.taskpilot.projects.chat.service.ProjectChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ProjectChatWebSocketController {

    private final ProjectChatService projectChatService;

    @MessageMapping("/projects/{projectId}/chat.sendMessage")
    public ChatMessageResponse handleSendMessage(
            @DestinationVariable Long projectId,
            @Payload SendChatMessageRequest request,
            Principal principal) {
        String email = principal != null ? principal.getName() : null;
        log.debug("Received STOMP chat message for project {} from {}", projectId, email);
        return projectChatService.sendMessage(projectId, email, request);
    }
}
