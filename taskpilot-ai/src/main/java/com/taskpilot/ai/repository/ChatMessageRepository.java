package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.ChatMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    Page<ChatMessageEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.sessionId = :sessionId ORDER BY m.createdAt DESC")
    List<ChatMessageEntity> findLastNBySessionId(Long sessionId, Pageable pageable);

    Optional<ChatMessageEntity> findFirstBySessionIdAndSenderAndClientMessageId(
            Long sessionId,
            ChatMessageEntity.SenderType sender,
            String clientMessageId);

    long countBySessionId(Long sessionId);
}
