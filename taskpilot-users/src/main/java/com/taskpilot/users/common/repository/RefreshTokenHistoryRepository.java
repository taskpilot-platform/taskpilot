package com.taskpilot.users.common.repository;

import com.taskpilot.users.common.entity.RefreshTokenHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenHistoryRepository extends JpaRepository<RefreshTokenHistoryEntity, Long> {

    Optional<RefreshTokenHistoryEntity> findByTokenHash(String tokenHash);

    long deleteByExpiresAtBefore(Instant cutoff);
}
