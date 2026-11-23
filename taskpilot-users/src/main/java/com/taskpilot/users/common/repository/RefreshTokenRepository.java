package com.taskpilot.users.common.repository;

import com.taskpilot.users.common.entity.RefreshTokenEntity;
import com.taskpilot.users.common.entity.UserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findBySessionId(UUID sessionId);

    @Meta(comment = "RefreshTokenRepository.findUserIdByTokenHash")
    @Query("SELECT r.user.id FROM RefreshTokenEntity r WHERE r.tokenHash = :tokenHash")
    Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Meta(comment = "RefreshTokenRepository.findByTokenHashForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshTokenEntity r JOIN FETCH r.user WHERE r.tokenHash = :tokenHash")
    Optional<RefreshTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Meta(comment = "RefreshTokenRepository.findActiveByUserIdForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshTokenEntity r WHERE r.user.id = :userId AND r.revokedAt IS NULL")
    List<RefreshTokenEntity> findActiveByUserIdForUpdate(@Param("userId") Long userId);

    @Meta(comment = "RefreshTokenRepository.findBySessionIdForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshTokenEntity r WHERE r.sessionId = :sessionId")
    Optional<RefreshTokenEntity> findBySessionIdForUpdate(@Param("sessionId") UUID sessionId);

    void deleteByUser(UserEntity user);
}
