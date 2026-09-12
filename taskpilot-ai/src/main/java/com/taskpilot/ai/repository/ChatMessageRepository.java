package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.ChatMessageEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    Page<ChatMessageEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    Window<ChatMessageEntity> findBySessionIdOrderByCreatedAtDescIdDesc(Long sessionId, ScrollPosition position, Limit limit);

    @Meta(comment = "ChatMessageRepository.findLastNBySessionId")
    @Query("SELECT m FROM ChatMessageEntity m WHERE m.sessionId = :sessionId ORDER BY m.createdAt DESC")
    List<ChatMessageEntity> findLastNBySessionId(Long sessionId, Pageable pageable);

    Optional<ChatMessageEntity> findFirstBySessionIdAndSenderAndClientMessageId(
            Long sessionId,
            ChatMessageEntity.SenderType sender,
            String clientMessageId);

    long countBySessionId(Long sessionId);

    @Meta(comment = "ChatMessageRepository.countBySessionIdIn")
    @Query("SELECT m.sessionId, COUNT(m) FROM ChatMessageEntity m WHERE m.sessionId IN :sessionIds GROUP BY m.sessionId")
    List<Object[]> countBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);
}
