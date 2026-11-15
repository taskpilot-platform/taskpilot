package com.taskpilot.infrastructure.config.security.oauth2;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface OAuth2AuthorizationRequestJpaRepository
        extends JpaRepository<OAuth2AuthorizationRequestEntity, String> {

    @Query("""
            SELECT COUNT(e) FROM OAuth2AuthorizationRequestEntity e
            WHERE e.consumedAt IS NULL AND e.expiresAt > :now
            """)
    long countActive(@Param("now") Instant now);

    boolean existsByHandleHashAndNonceHashAndConsumedAtIsNullAndExpiresAtAfter(
            String handleHash,
            String nonceHash,
            Instant now);

    @Modifying
    @Query("""
            DELETE FROM OAuth2AuthorizationRequestEntity e
            WHERE e.expiresAt <= :now
               OR (e.consumedAt IS NOT NULL AND e.consumedAt <= :consumedCutoff)
            """)
    int purgeExpiredOrOldConsumed(
            @Param("now") Instant now,
            @Param("consumedCutoff") Instant consumedCutoff);
}
