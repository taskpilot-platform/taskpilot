package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.TaskCommandIdempotencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TaskCommandIdempotencyRepository extends JpaRepository<TaskCommandIdempotencyEntity, Long> {
    Optional<TaskCommandIdempotencyEntity> findByActorUserIdAndOperationAndRequestId(
            Long actorUserId,
            String operation,
            UUID requestId);
}
