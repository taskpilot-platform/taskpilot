package com.taskpilot.ai.service;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {
    private final ChatSessionRepository sessionRepository;
    @Transactional
    public ChatSessionEntity createSession(Long userId, String title) {
        ChatSessionEntity session = ChatSessionEntity.builder()
                .userId(userId)
                .title(title)
                .build();
        ChatSessionEntity saved = sessionRepository.save(session);
        log.info("[ChatSession] Created session {} for user {}", saved.getId(), userId);
        return saved;
    }
    @Transactional(readOnly = true)
    public Page<ChatSessionEntity> getUserSessions(Long userId, Pageable pageable) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId, pageable);
    }
    @Transactional(readOnly = true)
    public ChatSessionEntity getSession(Long sessionId, Long userId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Chat session not found"));
    }
    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        ChatSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Chat session not found"));
        sessionRepository.delete(session);
        log.info("[ChatSession] Deleted session {} for user {}", sessionId, userId);
    }
    @Transactional
    public void updateTitle(Long sessionId, Long userId, String newTitle) {
        int updated = sessionRepository.updateTitle(sessionId, userId, newTitle);
        if (updated == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Chat session not found or access denied");
        }
    }
}

