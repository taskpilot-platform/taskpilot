package com.taskpilot.infrastructure.config.security.oauth2;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "oauth2_authorization_requests", schema = "public")
public class OAuth2AuthorizationRequestEntity {

    @Id
    @Column(name = "handle_hash", length = 64, nullable = false)
    private String handleHash;

    @Column(name = "nonce_hash", length = 64, nullable = false)
    private String nonceHash;

    @Column(name = "state_hash", length = 64, nullable = false, unique = true)
    private String stateHash;

    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    @Column(name = "purpose", length = 16, nullable = false)
    private String purpose;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "redirect_uri", length = 512, nullable = false)
    private String redirectUri;

    @Column(name = "payload_json", columnDefinition = "TEXT", nullable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
