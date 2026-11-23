package com.taskpilot.users.common.repository;

import com.taskpilot.users.common.entity.PasswordResetTokenEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.EmailTokenPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, Long> {

    Optional<PasswordResetTokenEntity> findByToken(String token);

    @Meta(comment = "PasswordResetTokenRepository.findUserIdByTokenHash")
    @Query("SELECT t.user.id FROM PasswordResetTokenEntity t WHERE t.tokenHash = :tokenHash")
    Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Meta(comment = "PasswordResetTokenRepository.findByTokenHashForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PasswordResetTokenEntity t WHERE t.tokenHash = :tokenHash")
    Optional<PasswordResetTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Meta(comment = "PasswordResetTokenRepository.invalidateActiveByUserIdAndPurpose")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetTokenEntity t SET t.invalidatedAt = :now "
            + "WHERE t.user.id = :userId AND t.purpose = :purpose "
            + "AND t.used = false AND t.consumedAt IS NULL AND t.invalidatedAt IS NULL")
    int invalidateActiveByUserIdAndPurpose(
            @Param("userId") Long userId,
            @Param("purpose") EmailTokenPurpose purpose,
            @Param("now") Instant now);

    @Meta(comment = "PasswordResetTokenRepository.invalidateAllActiveByUserId")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetTokenEntity t SET t.invalidatedAt = :now "
            + "WHERE t.user.id = :userId "
            + "AND t.used = false AND t.consumedAt IS NULL AND t.invalidatedAt IS NULL")
    int invalidateAllActiveByUserId(
            @Param("userId") Long userId,
            @Param("now") Instant now);

    @Meta(comment = "PasswordResetTokenRepository.deleteExpiredBefore")
    @Modifying
    @Query("DELETE FROM PasswordResetTokenEntity t WHERE t.expiryDate < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    void deleteByUser(UserEntity user);
}
