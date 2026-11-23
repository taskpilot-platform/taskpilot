package com.taskpilot.users.auth.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * PostgreSQL-backed atomic fixed-window counter store for multi-instance auth rate limiting.
 */
@Service
@Primary
public class DatabaseAuthAttemptStore implements AuthAttemptStore {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public DatabaseAuthAttemptStore(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    public DatabaseAuthAttemptStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long incrementAndGet(String action, String keyHash, Duration window, Instant now) {
        String bucketKey = bucketKey(action, keyHash);
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        OffsetDateTime expUtc = OffsetDateTime.ofInstant(now.plus(window), ZoneOffset.UTC);

        Long count = jdbcTemplate.queryForObject(
                """
                INSERT INTO public.auth_attempt_counters
                    (bucket_key, action, key_hash, window_started_at, expires_at, attempt_count)
                VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT (bucket_key) DO UPDATE SET
                    attempt_count = CASE
                        WHEN public.auth_attempt_counters.expires_at <= ? THEN 1
                        ELSE public.auth_attempt_counters.attempt_count + 1
                    END,
                    window_started_at = CASE
                        WHEN public.auth_attempt_counters.expires_at <= ? THEN ?
                        ELSE public.auth_attempt_counters.window_started_at
                    END,
                    expires_at = CASE
                        WHEN public.auth_attempt_counters.expires_at <= ? THEN ?
                        ELSE public.auth_attempt_counters.expires_at
                    END
                RETURNING attempt_count
                """,
                Long.class,
                bucketKey, action, keyHash, nowUtc, expUtc,
                nowUtc,
                nowUtc, nowUtc,
                nowUtc, expUtc);
        return count != null ? count : 1L;
    }

    @Override
    @Transactional(readOnly = true)
    public long currentCount(String action, String keyHash, Instant now) {
        String bucketKey = bucketKey(action, keyHash);
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        List<Long> rows = jdbcTemplate.query(
                """
                SELECT attempt_count
                FROM public.auth_attempt_counters
                WHERE bucket_key = ? AND expires_at > ?
                """,
                (rs, rowNum) -> rs.getLong("attempt_count"),
                bucketKey,
                nowUtc);
        return rows.isEmpty() ? 0L : rows.getFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long tryAcquireCooldownSeconds(String action, String keyHash, Duration cooldown, Instant now) {
        String bucketKey = bucketKey(action, keyHash);
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        OffsetDateTime expUtc = OffsetDateTime.ofInstant(now.plus(cooldown), ZoneOffset.UTC);

        List<CooldownRow> rows = jdbcTemplate.query(
                """
                INSERT INTO public.auth_attempt_counters
                    (bucket_key, action, key_hash, window_started_at, expires_at, attempt_count)
                VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT (bucket_key) DO UPDATE SET
                    attempt_count = CASE
                        WHEN public.auth_attempt_counters.expires_at <= ? THEN 1
                        ELSE public.auth_attempt_counters.attempt_count + 1
                    END,
                    window_started_at = CASE
                        WHEN public.auth_attempt_counters.expires_at <= ? THEN ?
                        ELSE public.auth_attempt_counters.window_started_at
                    END,
                    expires_at = CASE
                        WHEN public.auth_attempt_counters.expires_at <= ? THEN ?
                        ELSE public.auth_attempt_counters.expires_at
                    END
                RETURNING attempt_count, expires_at
                """,
                (rs, rowNum) -> {
                    Timestamp ts = rs.getTimestamp("expires_at");
                    return new CooldownRow(rs.getLong("attempt_count"), ts != null ? ts.toInstant() : now);
                },
                bucketKey, action, keyHash, nowUtc, expUtc,
                nowUtc,
                nowUtc, nowUtc,
                nowUtc, expUtc);
        if (rows.isEmpty()) {
            return 0L;
        }
        CooldownRow row = rows.getFirst();
        if (row.attemptCount() == 1L) {
            return 0L;
        }
        return Math.max(1L, Duration.between(now, row.expiresAt()).toSeconds());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(String action, String keyHash) {
        jdbcTemplate.update(
                "DELETE FROM public.auth_attempt_counters WHERE bucket_key = ?",
                bucketKey(action, keyHash));
    }

    @Scheduled(fixedDelayString = "${application.security.auth-throttle.cleanup-interval-ms:300000}")
    public void scheduledPurge() {
        purgeExpired(clock.instant());
    }

    @Override
    @Transactional
    public int purgeExpired(Instant now) {
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return jdbcTemplate.update(
                "DELETE FROM public.auth_attempt_counters WHERE expires_at <= ?",
                nowUtc);
    }

    private static String bucketKey(String action, String keyHash) {
        String combined = action + ":" + keyHash;
        return combined.length() > 128 ? combined.substring(0, 128) : combined;
    }

    private record CooldownRow(long attemptCount, Instant expiresAt) {}
}
