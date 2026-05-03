package com.taskpilot.ai.controller;

import com.taskpilot.ai.dto.*;
import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.service.*;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.dto.ApiResponse;
import com.taskpilot.infrastructure.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Tag(name = "AI Copilot", description = "TaskPilot AI Copilot chat and auto-assignment APIs")
public class AiChatController {
    private final ChatSessionService sessionService;
    private final ChatMessageService messageService;
    private final AiStreamingService streamingService;
    private final ChatStreamStatusService chatStreamStatusService;
    private final AiLogService aiLogService;
    private final AutoAssignmentService autoAssignmentService;
    private final UserIdentityPort userIdentityPort;

    @Value("${ai.chat.max-user-input-chars:1500}")
    private int maxUserInputChars;

    @Operation(summary = "Create a new AI chat session")
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChatSessionResponse> createSession(
            @Valid @RequestBody(required = false) CreateSessionRequest request,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        String title = request != null ? request.title() : null;
        ChatSessionEntity session = sessionService.createSession(userId, title);
        return ApiResponse.success(201, "Session created", toSessionResponse(session, 0));
    }

    @Operation(summary = "List all chat sessions for the current user")
    @GetMapping("/sessions")
    public ApiResponse<Page<ChatSessionResponse>> getSessions(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        Pageable pageable = PageRequest.of(page, size);
        Page<ChatSessionEntity> sessions = sessionService.getUserSessions(userId, pageable);
        Page<ChatSessionResponse> response = sessions
                .map(s -> toSessionResponse(s, messageService.countMessages(s.getId())));
        return ApiResponse.success(response);
    }

    @Operation(summary = "Get a specific chat session")
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<ChatSessionResponse> getSession(@PathVariable Long sessionId,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        ChatSessionEntity session = sessionService.getSession(sessionId, userId);
        return ApiResponse
                .success(toSessionResponse(session, messageService.countMessages(sessionId)));
    }

    @Operation(summary = "Update session title")
    @PatchMapping("/sessions/{sessionId}/title")
    public ApiResponse<Void> updateTitle(@PathVariable Long sessionId, @RequestParam String title,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        sessionService.updateTitle(sessionId, userId, title);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Delete a chat session (and all its messages)")
    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        sessionService.deleteSession(sessionId, userId);
        return ApiResponse.success(204, "Session deleted", null);
    }

    @Operation(summary = "Stream AI chat response via SSE")
    @GetMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@PathVariable Long sessionId, @RequestParam String message,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        validateUserMessage(message);
        log.info("[AiChat] Stream request — session: {}, user: {}, msg: {}chars", sessionId, userId,
                message.length());
        return streamingService.streamChat(sessionId, userId, message, null);
    }

    @Operation(summary = "Stream AI chat response via SSE (POST body)")
    @PostMapping(value = "/sessions/{sessionId}/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChatPost(@PathVariable Long sessionId,
            @Valid @RequestBody StreamChatRequest request,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        String message = request.message();
        String clientMessageId = request.clientMessageId();
        validateUserMessage(message);
        log.info("[AiChat] Stream POST request — session: {}, user: {}, msg: {}chars", sessionId,
                userId, message.length());
        return streamingService.streamChat(sessionId, userId, message, clientMessageId);
    }

    @Operation(summary = "Get paginated message history for a session")
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<Page<ChatMessageResponse>> getMessages(@PathVariable Long sessionId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        Pageable pageable = PageRequest.of(page, size);
        Page<ChatMessageEntity> messages = messageService.getMessages(sessionId, userId, pageable);
        return ApiResponse.success(messages.map(this::toMessageResponse));
    }

    @Operation(summary = "Get latest stream processing phase for a chat session")
    @GetMapping("/sessions/{sessionId}/stream-status")
    public ApiResponse<ChatStreamStatusResponse> getStreamStatus(@PathVariable Long sessionId,
            @RequestParam(required = false) String clientMessageId,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        return ApiResponse.success(chatStreamStatusService
                .getStatus(sessionId, userId, clientMessageId)
                .orElse(null));
    }

    @Operation(summary = "View AI activity audit logs")
    @GetMapping("/logs")
    public ApiResponse<Page<AiLogResponse>> getLogs(@RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        Long currentUserId = resolveUserId(authentication);
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        Pageable pageable = PageRequest.of(page, size);
        Page<AiLogEntity> logs;
        if (isAdmin) {
            logs = aiLogService.getLogsForAdmin(userId, projectId, from, to, pageable);
        } else {
            logs = aiLogService.getLogsForUser(currentUserId, pageable);
        }
        return ApiResponse.success(logs.map(this::toLogResponse));
    }

    @Operation(summary = "Update human feedback on an AI log (ACCEPTED / REJECTED)")
    @PatchMapping("/logs/{logId}/feedback")
    public ApiResponse<Void> updateFeedback(@PathVariable Long logId,
            @RequestParam String feedback) {
        if (!List.of("ACCEPTED", "REJECTED", "PENDING").contains(feedback.toUpperCase())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Feedback must be ACCEPTED, REJECTED, or PENDING");
        }
        aiLogService.updateFeedback(logId, feedback.toUpperCase());
        return ApiResponse.success(null);
    }

    @Operation(summary = "Request AI auto-assignment recommendations")
    @PostMapping("/auto-assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')") // PM role is project-level, checked in service
    public ApiResponse<AutoAssignmentResponse> autoAssign(
            @Valid @RequestBody AutoAssignmentRequest request,
            Authentication authentication) {
        Long userId = resolveUserId(authentication);
        log.info("[AutoAssign] Request from user {} for project {}", userId,
                request.projectId());
        AutoAssignmentResponse response = autoAssignmentService.recommend(request.projectId(),
                request.requiredSkills(), request.taskDifficulty(), userId);
        return ApiResponse.success(response);
    }

    private Long resolveUserId(Authentication authentication) {
        return userIdentityPort.findUserIdByEmail(authentication.getName())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(),
                        "User not found"));
    }

    private void validateUserMessage(String message) {
        if (message == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Message is required");
        }

        if (message.length() > maxUserInputChars) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Message exceeds " + maxUserInputChars + " characters");
        }
    }

    private ChatSessionResponse toSessionResponse(ChatSessionEntity s, long messageCount) {
        return ChatSessionResponse.builder().id(s.getId()).title(s.getTitle())
                .createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt()).messageCount(messageCount)
                .build();
    }

    private ChatMessageResponse toMessageResponse(ChatMessageEntity m) {
        return ChatMessageResponse.builder().id(m.getId()).sessionId(m.getSessionId())
                .sender(m.getSender().name()).content(m.getContent()).createdAt(m.getCreatedAt())
                .build();
    }

    private AiLogResponse toLogResponse(AiLogEntity l) {
        return AiLogResponse.builder().id(l.getId()).userId(l.getUserId())
                .projectId(l.getProjectId()).sessionId(l.getSessionId()).request(l.getRequest())
                .response(l.getResponse()).reasoning(l.getReasoning())
                .actionTaken(l.getActionTaken()).modelUsed(l.getModelUsed())
                .tokensUsed(l.getTokensUsed()).durationMs(l.getDurationMs())
                .humanFeedback(l.getHumanFeedback()).createdAt(l.getCreatedAt()).build();
    }
}
