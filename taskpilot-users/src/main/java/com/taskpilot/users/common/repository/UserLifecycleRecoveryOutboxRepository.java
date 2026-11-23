package com.taskpilot.users.common.repository;

import com.taskpilot.users.common.entity.UserLifecycleRecoveryOutboxEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserLifecycleRecoveryOutboxRepository extends JpaRepository<UserLifecycleRecoveryOutboxEntity, UUID> {

    @Meta(comment = "UserLifecycleRecoveryOutboxRepository.findDuePendingIds")
    @Query("SELECT o.eventId FROM UserLifecycleRecoveryOutboxEntity o "
            + "WHERE o.status = 'PENDING' AND o.nextAttemptAt <= :now "
            + "AND (o.leaseUntil IS NULL OR o.leaseUntil <= :now) "
            + "ORDER BY o.occurredAt ASC, o.tokenVersion ASC")
    List<UUID> findDuePendingIds(@Param("now") Instant now, Pageable pageable);

    @Meta(comment = "UserLifecycleRecoveryOutboxRepository.findByEventIdForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM UserLifecycleRecoveryOutboxEntity o WHERE o.eventId = :eventId")
    Optional<UserLifecycleRecoveryOutboxEntity> findByEventIdForUpdate(@Param("eventId") UUID eventId);

    List<UserLifecycleRecoveryOutboxEntity> findByUserIdOrderByOccurredAtAsc(Long userId);
}
