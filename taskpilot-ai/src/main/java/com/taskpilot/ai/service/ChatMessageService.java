package com.taskpilot.ai.service;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class ChatMessageService {
    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    @Transactional(readOnly = true)
    public Page<ChatMessageEntity> getMessages(Long sessionId, Long userId, Pageable pageable) {
        sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Chat session not found"));
        return messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
    }
    @Transactional(readOnly = true)
    public long countMessages(Long sessionId) {
        return messageRepository.countBySessionId(sessionId);
    }
}

