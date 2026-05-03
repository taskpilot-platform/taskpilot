package com.taskpilot.ai.service;

import com.taskpilot.ai.dto.ChatStreamStatusResponse;
import com.taskpilot.ai.entity.AiChatRequestEntity;
import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.repository.AiChatRequestRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChatStreamStatusService {

    private final AiChatRequestRepository aiChatRequestRepository;
    private final ChatSessionRepository chatSessionRepository;

    @Transactional
    public void upsertQueued(Long sessionId, Long userId, String clientMessageId) {
        AiChatRequestEntity entity = aiChatRequestRepository
                .findBySessionIdAndClientMessageId(sessionId, clientMessageId)
                .orElseGet(() -> AiChatRequestEntity.builder()
                        .sessionId(sessionId)
                        .userId(userId)
                        .clientMessageId(clientMessageId)
                        .build());

        entity.setUserId(userId);
        entity.setPhase(Phase.QUEUED);
        entity.setErrorMessage(null);
        entity.setAssistantMessageId(null);
        aiChatRequestRepository.save(entity);
    }

    @Transactional
    public void updatePhase(Long sessionId,
            String clientMessageId,
            Phase phase,
            String modelUsed,
            Long assistantMessageId,
            String errorMessage) {
        aiChatRequestRepository.findBySessionIdAndClientMessageId(sessionId, clientMessageId)
                .ifPresent(entity -> {
                    entity.setPhase(phase);
                    if (modelUsed != null) {
                        entity.setModelUsed(modelUsed);
                    }
                    if (assistantMessageId != null) {
                        entity.setAssistantMessageId(assistantMessageId);
                    }
                    entity.setErrorMessage(errorMessage);
                    aiChatRequestRepository.save(entity);
                });
    }

    @Transactional(readOnly = true)
    public Optional<ChatStreamStatusResponse> getStatus(Long sessionId,
            Long userId,
            String clientMessageId) {
        ChatSessionEntity session = chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Chat session not found"));

        Optional<AiChatRequestEntity> status = (clientMessageId == null || clientMessageId.isBlank())
                ? aiChatRequestRepository
                        .findTopBySessionIdAndUserIdOrderByUpdatedAtDesc(session.getId(), userId)
                : aiChatRequestRepository.findBySessionIdAndClientMessageId(session.getId(),
                        clientMessageId);

        return status.map(this::toResponse);
    }

    private ChatStreamStatusResponse toResponse(AiChatRequestEntity entity) {
        return ChatStreamStatusResponse.builder()
                .sessionId(entity.getSessionId())
                .clientMessageId(entity.getClientMessageId())
                .phase(entity.getPhase().name())
                .modelUsed(entity.getModelUsed())
                .assistantMessageId(entity.getAssistantMessageId())
                .errorMessage(entity.getErrorMessage())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
