package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.AiChatRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AiChatRequestRepository extends JpaRepository<AiChatRequestEntity, Long> {

    Optional<AiChatRequestEntity> findBySessionIdAndClientMessageId(Long sessionId,
            String clientMessageId);

    Optional<AiChatRequestEntity> findTopBySessionIdAndUserIdOrderByUpdatedAtDesc(Long sessionId,
            Long userId);
}
