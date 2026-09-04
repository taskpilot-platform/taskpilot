package com.taskpilot.ai.service;

import com.taskpilot.ai.context.ChatHistoryCompactor;
import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.entity.ChatMessageEntity.SenderType;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.prompt.SystemPromptBuilder;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.ai.streaming.engine.StreamingChatEngine;
import com.taskpilot.ai.streaming.sse.AiSseTransport;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiStreamingServiceTest {

    @Mock
    private ChatSessionRepository sessionRepository;

    @Mock
    private ChatMessageRepository messageRepository;

    @Mock
    private SmartRoutingService routingService;

    @Mock
    private ChatStreamStatusService chatStreamStatusService;

    @Mock
    private SessionChatMemoryService sessionChatMemoryService;

    @Mock
    private StreamingChatEngine streamingChatEngine;

    @Mock
    private SystemPromptBuilder promptBuilder;

    @Mock
    private ChatHistoryCompactor compactor;

    @Mock
    private AiSseTransport sseTransport;

    @Mock
    private StreamingChatModel chatModel;

    private AiStreamingService streamingService;

    @BeforeEach
    void setUp() {
        streamingService = new AiStreamingService(
                sessionRepository,
                messageRepository,
                routingService,
                chatStreamStatusService,
                sessionChatMemoryService,
                streamingChatEngine,
                promptBuilder,
                compactor,
                sseTransport);
    }

    @Test
    void streamChat_WhenSessionNotFound_ShouldThrowSecurityException() {
        when(sessionRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.empty());

        assertThrows(SecurityException.class, () ->
                streamingService.streamChat(1L, 100L, "Hello", "msg-1"));
    }

    @Test
    void streamChat_WhenDuplicateClientMessageId_ShouldCompleteEarly() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(1L);
        when(sessionRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(session));

        ChatMessageEntity existingMessage = ChatMessageEntity.builder()
                .sessionId(1L)
                .sender(SenderType.USER)
                .clientMessageId("msg-dup")
                .build();
        when(messageRepository.findFirstBySessionIdAndSenderAndClientMessageId(1L, SenderType.USER, "msg-dup"))
                .thenReturn(Optional.of(existingMessage));

        SseEmitter emitter = streamingService.streamChat(1L, 100L, "Hello", "msg-dup");

        assertNotNull(emitter);
        verify(sseTransport).safeSend(any(), eq("phase"), eq(Phase.FINALIZED.name()), any());
        verify(sseTransport).safeSend(any(), eq("done"), eq(""), any());
        verify(sseTransport).safeComplete(any(), any());
    }

    @Test
    void streamChat_ValidRequest_ShouldInitiateRoutingAndStream() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(1L);
        when(sessionRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(session));
        when(messageRepository.findFirstBySessionIdAndSenderAndClientMessageId(eq(1L), eq(SenderType.USER), anyString()))
                .thenReturn(Optional.empty());

        when(promptBuilder.buildSystemPrompt(100L)).thenReturn("System Prompt");
        List<ChatMessage> history = new ArrayList<>(List.of(UserMessage.from("Hello")));
        when(sessionChatMemoryService.appendUserMessage(eq(1L), anyString(), eq("Hello"))).thenReturn(history);
        when(promptBuilder.withSystemPrompt(any(), any())).thenReturn(history);
        when(compactor.compactHistoryForRequest(any(), anyString())).thenReturn(history);

        SmartRoutingService.RoutingDecision decision = new SmartRoutingService.RoutingDecision(
                chatModel, "mock-model", false, false);
        when(routingService.route(anyString(), anyString())).thenReturn(decision);

        SseEmitter emitter = streamingService.streamChat(1L, 100L, "Hello", "msg-valid");

        assertNotNull(emitter);
        verify(chatStreamStatusService).upsertQueued(eq(1L), eq(100L), eq("msg-valid"));
        verify(chatStreamStatusService).updatePhase(eq(1L), eq("msg-valid"), eq(Phase.ROUTING), any(), any(), any());
        verify(chatStreamStatusService).updatePhase(eq(1L), eq("msg-valid"), eq(Phase.THINKING), eq("mock-model"), any(), any());
    }
}
