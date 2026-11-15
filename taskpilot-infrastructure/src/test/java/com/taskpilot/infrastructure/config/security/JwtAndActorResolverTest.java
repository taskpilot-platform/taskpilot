package com.taskpilot.infrastructure.config.security;

import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAndActorResolverTest {

    private static final String TEST_SECRET =
            "taskpilot-p12-jwt-signing-hmac-key-0123456789abcdef0123456789abcdef";

    @SuppressWarnings("unchecked")
    private JwtService newJwtService() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(TEST_SECRET);
        properties.setExpiration(900_000L);
        ObjectProvider<StringRedisTemplate> redis = Mockito.mock(ObjectProvider.class);
        return new JwtService(properties, new JwtTokenBlocklistService(redis));
    }

    @Test
    void parseAndValidate_rejectsWrongIssuerAudienceSchemaAndRevokedJti() {
        JwtService jwtService = newJwtService();
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();

        String wrongIssuer = Jwts.builder()
                .issuer("other-issuer")
                .audience().add("taskpilot-api").and()
                .subject("42")
                .id(UUID.randomUUID().toString())
                .claim("token_schema", 2)
                .claim("ver", 1L)
                .claim("sid", UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(key)
                .compact();
        assertThat(jwtService.parseAndValidate(wrongIssuer)).isEmpty();

        String wrongAudience = Jwts.builder()
                .issuer("taskpilot")
                .audience().add("wrong-aud").and()
                .subject("42")
                .id(UUID.randomUUID().toString())
                .claim("token_schema", 2)
                .claim("ver", 1L)
                .claim("sid", UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(key)
                .compact();
        assertThat(jwtService.parseAndValidate(wrongAudience)).isEmpty();

        String valid = jwtService.generateToken(42L, 0L, UUID.randomUUID(), "USER");
        assertThat(jwtService.parseAndValidate(valid)).isPresent();
        jwtService.revokeToken(valid);
        assertThat(jwtService.parseAndValidate(valid)).isEmpty();
    }

    @Test
    void currentActorResolver_rejectsNullAnonymousAndRawStringPrincipals() {
        assertThatThrownBy(() -> CurrentActorResolver.userId((org.springframework.security.core.Authentication) null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CurrentActorResolver.resolve(
                new UsernamePasswordAuthenticationToken("alice@taskpilot.io", null, List.of())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CurrentActorResolver.resolve(
                new AnonymousAuthenticationToken("k", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))))
                .isInstanceOf(BusinessException.class);

        TaskPilotPrincipal principal = new TaskPilotPrincipal(
                new CurrentActor(77L, "bob@taskpilot.io", "USER", 2L));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(CurrentActorResolver.userId(auth)).isEqualTo(77L);
        assertThat(CurrentActorResolver.resolve(auth).currentEmail()).isEqualTo("bob@taskpilot.io");
    }
}
