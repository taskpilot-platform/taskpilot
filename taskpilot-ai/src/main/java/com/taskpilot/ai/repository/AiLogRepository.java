package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.AiLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;

@Repository
public interface AiLogRepository extends JpaRepository<AiLogEntity, Long> {
        @Query("""
                        SELECT l FROM AiLogEntity l
                        WHERE (:userId IS NULL OR l.userId = :userId)
                          AND (:projectId IS NULL OR l.projectId = :projectId)
                          AND (:from IS NULL OR l.createdAt >= :from)
                          AND (:to IS NULL OR l.createdAt <= :to)
                        ORDER BY l.createdAt DESC
                        """)
        Page<AiLogEntity> findByFilters(
                        @Param("userId") Long userId,
                        @Param("projectId") Long projectId,
                        @Param("from") Instant from,
                        @Param("to") Instant to,
                        Pageable pageable);

        Page<AiLogEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

        Page<AiLogEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);
}
