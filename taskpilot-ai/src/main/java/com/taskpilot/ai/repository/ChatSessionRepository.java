package com.taskpilot.ai.repository;
import com.taskpilot.ai.entity.ChatSessionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;
@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, Long> {
    Page<ChatSessionEntity> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable pageable);
    Optional<ChatSessionEntity> findByIdAndUserId(Long id, Long userId);
    @Modifying
    @Query("UPDATE ChatSessionEntity s SET s.title = :title WHERE s.id = :id AND s.userId = :userId")
    int updateTitle(Long id, Long userId, String title);
}

