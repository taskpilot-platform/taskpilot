package com.taskpilot.infrastructure.config.security.oauth2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL-backed OAuth2 authorization request store.
 * Uses SHA-256 hashes of opaque handle, browser nonce, and OAuth state,
 * bounded allowlist JSON payload (preserving PKCE & OIDC metadata),
 * SERIALIZABLE capacity enforcement, and single-SQL atomic consume.
 */
@Slf4j
@Service
public class DatabaseOAuth2AuthorizationRequestStore implements OAuth2AuthorizationRequestStore {

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_SERIALIZATION_RETRIES = 6;
    private static final int MAX_PAYLOAD_JSON_BYTES = 16_384;
    private static final Duration CONSUMED_RETENTION = Duration.ofMinutes(5);

    private static final Set<String> ALLOWED_ADDITIONAL_PARAMETERS = Set.of(
            "code_challenge",
            "code_challenge_method",
            "nonce",
            "access_type",
            "prompt",
            "response_mode",
            "hd",
            "include_granted_scopes",
            "login_hint",
            "scope",
            "state"
    );

    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "registration_id",
            "code_verifier",
            "nonce"
    );

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OAuth2AuthorizationRequestJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final OAuth2Properties oAuth2Properties;
    private final TransactionTemplate serializableTx;
    private final TransactionTemplate writeTx;
    private final Clock clock;

    @Autowired
    public DatabaseOAuth2AuthorizationRequestStore(
            OAuth2AuthorizationRequestJpaRepository repository,
            JdbcTemplate jdbcTemplate,
            OAuth2Properties oAuth2Properties,
            PlatformTransactionManager transactionManager) {
        this(repository, jdbcTemplate, oAuth2Properties, transactionManager, Clock.systemUTC());
    }

    public DatabaseOAuth2AuthorizationRequestStore(
            OAuth2AuthorizationRequestJpaRepository repository,
            JdbcTemplate jdbcTemplate,
            OAuth2Properties oAuth2Properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.oAuth2Properties = oAuth2Properties;
        this.clock = clock;

        this.serializableTx = new TransactionTemplate(transactionManager);
        this.serializableTx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);

        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public StoredHandle put(SaveCommand command) {
        validateCommand(command);
        String payloadJson = serializePayload(toBoundedPayload(command.authorizationRequest(), command.provider()));
        String nonceHash = sha256Hex(command.browserNonce());
        String stateHash = sha256Hex(command.authorizationRequest().getState());
        String handle = randomToken();
        String handleHash = sha256Hex(handle);

        for (int attempt = 1; attempt <= MAX_SERIALIZATION_RETRIES; attempt++) {
            try {
                Instant expiresAt = serializableTx.execute(status -> {
                    Instant now = clock.instant();
                    purgeExpiredInternal(now);
                    long activeCount = countActiveInternal(now);
                    if (activeCount >= oAuth2Properties.getMaxPendingRequests()) {
                        throw new IllegalStateException("OAuth2 pending authorization request capacity exceeded");
                    }
                    Instant exp = now.plusSeconds(oAuth2Properties.getRequestTtlSeconds());
                    try {
                        jdbcTemplate.update(
                                """
                                INSERT INTO public.oauth2_authorization_requests
                                    (handle_hash, nonce_hash, state_hash, provider, purpose, target_user_id,
                                     redirect_uri, payload_json, created_at, expires_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                                handleHash,
                                nonceHash,
                                stateHash,
                                command.provider(),
                                command.purpose().name(),
                                command.targetUserId(),
                                command.callbackRedirectUri(),
                                payloadJson,
                                toUtcOffset(now),
                                toUtcOffset(exp));
                    } catch (DuplicateKeyException dup) {
                        throw new IllegalStateException("Duplicate OAuth2 authorization state", dup);
                    }
                    return exp;
                });
                return new StoredHandle(handle, expiresAt);
            } catch (ConcurrencyFailureException ex) {
                if (attempt == MAX_SERIALIZATION_RETRIES || !isSerializationFailure(ex)) {
                    throw ex;
                }
            }
        }
        throw new IllegalStateException("Unable to store OAuth2 authorization request after serialization retries");
    }

    @Override
    public Optional<OAuth2AuthorizationRequest> load(LookupKey key) {
        if (!isValidLookupKey(key)) {
            return Optional.empty();
        }
        String handleHash = sha256Hex(key.handle());
        String nonceHash = sha256Hex(key.browserNonce());
        String stateHash = sha256Hex(key.state());
        OffsetDateTime now = toUtcOffset(clock.instant());

        String sql = buildLookupSql(
                """
                SELECT payload_json
                FROM public.oauth2_authorization_requests
                WHERE handle_hash = ?
                  AND nonce_hash = ?
                  AND state_hash = ?
                  AND provider = ?
                  AND purpose = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                """,
                key.expectedRedirectUri());

        List<String> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getString("payload_json"),
                buildLookupArgs(handleHash, nonceHash, stateHash, key, now));

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(deserializePayload(rows.getFirst(), key.state()));
    }

    @Override
    public Optional<OAuth2AuthorizationRequest> consume(LookupKey key) {
        if (!isValidLookupKey(key)) {
            return Optional.empty();
        }
        String handleHash = sha256Hex(key.handle());
        String nonceHash = sha256Hex(key.browserNonce());
        String stateHash = sha256Hex(key.state());
        OffsetDateTime now = toUtcOffset(clock.instant());

        String sql = buildLookupSql(
                """
                UPDATE public.oauth2_authorization_requests
                SET consumed_at = ?
                WHERE handle_hash = ?
                  AND nonce_hash = ?
                  AND state_hash = ?
                  AND provider = ?
                  AND purpose = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                """,
                key.expectedRedirectUri()) + " RETURNING payload_json";

        List<Object> params = new ArrayList<>();
        params.add(now);
        for (Object arg : buildLookupArgs(handleHash, nonceHash, stateHash, key, now)) {
            params.add(arg);
        }

        List<String> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getString("payload_json"),
                params.toArray());

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(deserializePayload(rows.getFirst(), key.state()));
    }

    @Override
    public boolean isActive(String handle, String browserNonce) {
        if (handle == null || handle.isBlank() || browserNonce == null || browserNonce.isBlank()) {
            return false;
        }
        return repository.existsByHandleHashAndNonceHashAndConsumedAtIsNullAndExpiresAtAfter(
                sha256Hex(handle),
                sha256Hex(browserNonce),
                clock.instant());
    }

    @Scheduled(fixedDelayString = "${application.security.oauth2.cleanup-interval-ms:60000}")
    @Override
    public int purgeExpired() {
        Integer deleted = writeTx.execute(status -> purgeExpiredInternal(clock.instant()));
        return deleted != null ? deleted : 0;
    }

    private int purgeExpiredInternal(Instant now) {
        OffsetDateTime nowUtc = toUtcOffset(now);
        OffsetDateTime consumedCutoff = toUtcOffset(now.minus(CONSUMED_RETENTION));
        return jdbcTemplate.update(
                """
                DELETE FROM public.oauth2_authorization_requests
                WHERE expires_at <= ?
                   OR (consumed_at IS NOT NULL AND consumed_at <= ?)
                """,
                nowUtc,
                consumedCutoff);
    }

    private long countActiveInternal(Instant now) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM public.oauth2_authorization_requests
                WHERE consumed_at IS NULL AND expires_at > ?
                """,
                Long.class,
                toUtcOffset(now));
        return count != null ? count : 0L;
    }

    private static String buildLookupSql(String baseSql, String expectedRedirectUri) {
        if (expectedRedirectUri != null && !expectedRedirectUri.isBlank()) {
            return baseSql + "\n  AND redirect_uri = ?";
        }
        return baseSql;
    }

    private static Object[] buildLookupArgs(
            String handleHash,
            String nonceHash,
            String stateHash,
            LookupKey key,
            OffsetDateTime now) {
        if (key.expectedRedirectUri() != null && !key.expectedRedirectUri().isBlank()) {
            return new Object[] {
                    handleHash, nonceHash, stateHash,
                    key.expectedProvider(), key.expectedPurpose().name(), now, key.expectedRedirectUri()
            };
        }
        return new Object[] {
                handleHash, nonceHash, stateHash,
                key.expectedProvider(), key.expectedPurpose().name(), now
        };
    }

    private static boolean isValidLookupKey(LookupKey key) {
        return key != null
                && key.handle() != null && !key.handle().isBlank() && key.handle().length() <= 128
                && key.browserNonce() != null && !key.browserNonce().isBlank() && key.browserNonce().length() <= 128
                && key.state() != null && !key.state().isBlank() && key.state().length() <= 256
                && key.expectedProvider() != null && !key.expectedProvider().isBlank() && key.expectedProvider().length() <= 32
                && key.expectedPurpose() != null;
    }

    private static void validateCommand(SaveCommand command) {
        if (command == null || command.authorizationRequest() == null) {
            throw new IllegalArgumentException("OAuth2AuthorizationRequest must not be null");
        }
        OAuth2AuthorizationRequest req = command.authorizationRequest();
        if (req.getState() == null || req.getState().isBlank() || req.getState().length() > 256) {
            throw new IllegalArgumentException("OAuth2 state is missing or exceeds maximum length");
        }
        if (command.provider() == null || command.provider().isBlank() || command.provider().length() > 32) {
            throw new IllegalArgumentException("OAuth2 provider is missing or exceeds maximum length");
        }
        if (command.purpose() == null) {
            throw new IllegalArgumentException("OAuth2 purpose is required");
        }
        if (command.purpose() == Purpose.LOGIN && command.targetUserId() != null) {
            throw new IllegalArgumentException("LOGIN flow must not bind targetUserId");
        }
        if (command.purpose() == Purpose.LINK && command.targetUserId() == null) {
            throw new IllegalArgumentException("LINK flow requires targetUserId");
        }
        if (command.browserNonce() == null || command.browserNonce().isBlank() || command.browserNonce().length() > 128) {
            throw new IllegalArgumentException("Browser nonce is invalid");
        }
        validateHttpUri(command.callbackRedirectUri(), "callbackRedirectUri", 512);
    }

    private static BoundedPayload toBoundedPayload(OAuth2AuthorizationRequest req, String provider) {
        String authUri = validateHttpUri(req.getAuthorizationUri(), "authorizationUri", 512);
        String clientId = requireBoundedString(req.getClientId(), "clientId", 256);
        String redirectUri = validateHttpUri(req.getRedirectUri(), "redirectUri", 512);
        String authReqUri = req.getAuthorizationRequestUri() != null
                ? requireBoundedString(req.getAuthorizationRequestUri(), "authorizationRequestUri", 2048)
                : null;

        Set<String> rawScopes = req.getScopes();
        if (rawScopes.size() > 32) {
            throw new IllegalArgumentException("OAuth2 scopes exceed maximum count");
        }
        List<String> scopes = new ArrayList<>(rawScopes.size());
        for (String scope : rawScopes) {
            scopes.add(requireBoundedString(scope, "scope", 128));
        }

        Map<String, String> additionalParameters = filterBoundedMap(
                req.getAdditionalParameters(), ALLOWED_ADDITIONAL_PARAMETERS, "additionalParameters");
        Map<String, String> attributes = filterBoundedMap(
                req.getAttributes(), ALLOWED_ATTRIBUTES, "attributes");
        String attrRegId = attributes.get("registration_id");
        if (attrRegId != null && !attrRegId.equals(provider)) {
            throw new IllegalArgumentException("OAuth2 registration_id attribute does not match provider");
        }
        attributes.putIfAbsent("registration_id", provider);

        return new BoundedPayload(
                authUri,
                clientId,
                redirectUri,
                scopes,
                authReqUri,
                additionalParameters,
                attributes
        );
    }

    private static Map<String, String> filterBoundedMap(
            Map<String, Object> source,
            Set<String> allowedKeys,
            String fieldName) {
        Map<String, String> filtered = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return filtered;
        }
        if (source.size() > 16) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum entry count");
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || !allowedKeys.contains(key)) {
                continue;
            }
            Object val = entry.getValue();
            if (val == null) {
                continue;
            }
            if (!(val instanceof String || val instanceof Number || val instanceof Boolean)) {
                throw new IllegalArgumentException(fieldName + " contains unsupported value type for key " + key);
            }
            String strVal = String.valueOf(val);
            if (strVal.length() > 512) {
                throw new IllegalArgumentException(fieldName + " value exceeds maximum length for key " + key);
            }
            filtered.put(key, strVal);
        }
        return filtered;
    }

    private static String serializePayload(BoundedPayload payload) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(payload);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_JSON_BYTES) {
                throw new IllegalArgumentException("Serialized OAuth2 payload exceeds maximum allowed bytes");
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize bounded OAuth2 payload", e);
        }
    }

    private static OAuth2AuthorizationRequest deserializePayload(String payloadJson, String verifiedState) {
        try {
            BoundedPayload payload = OBJECT_MAPPER.readValue(payloadJson, BoundedPayload.class);
            OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri(payload.authorizationUri())
                    .clientId(payload.clientId())
                    .redirectUri(payload.redirectUri())
                    .scopes(new LinkedHashSet<>(payload.scopes()))
                    .state(verifiedState);
            if (payload.additionalParameters() != null && !payload.additionalParameters().isEmpty()) {
                builder.additionalParameters(params -> params.putAll(payload.additionalParameters()));
            }
            if (payload.attributes() != null && !payload.attributes().isEmpty()) {
                builder.attributes(attrs -> attrs.putAll(payload.attributes()));
            }
            if (payload.authorizationRequestUri() != null && !payload.authorizationRequestUri().isBlank()) {
                builder.authorizationRequestUri(payload.authorizationRequestUri());
            }
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt stored OAuth2 authorization payload", e);
        }
    }

    private static String validateHttpUri(String rawUri, String fieldName, int maxLength) {
        String bounded = requireBoundedString(rawUri, fieldName, maxLength);
        URI uri = URI.create(bounded);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be a valid http/https URI");
        }
        if (uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException(fieldName + " must not contain fragment or userinfo");
        }
        return bounded;
    }

    private static String requireBoundedString(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length of " + maxLength);
        }
        return value;
    }

    private static boolean isSerializationFailure(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof SQLException sqlEx && "40001".equals(sqlEx.getSQLState())) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    record BoundedPayload(
            String authorizationUri,
            String clientId,
            String redirectUri,
            List<String> scopes,
            String authorizationRequestUri,
            Map<String, String> additionalParameters,
            Map<String, String> attributes
    ) {}
}
