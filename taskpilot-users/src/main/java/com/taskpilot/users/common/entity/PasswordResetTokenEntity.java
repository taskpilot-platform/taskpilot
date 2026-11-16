package com.taskpilot.users.common.entity;

import com.taskpilot.infrastructure.base.BaseEntity;
import com.taskpilot.users.common.enums.EmailTokenPurpose;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetTokenEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "password_reset_tokens_id_seq_gen")
    @SequenceGenerator(name = "password_reset_tokens_id_seq_gen", sequenceName = "password_reset_tokens_id_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = true, unique = true)
    private String token;

    @Column(name = "token_hash", length = 64, unique = true)
    private String tokenHash;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private EmailTokenPurpose purpose = EmailTokenPurpose.PASSWORD_RESET;

    @Builder.Default
    @Column(name = "credential_version", nullable = false)
    private Long credentialVersion = 0L;

    @Column(name = "target_provider", length = 32)
    private String targetProvider;

    @Column(name = "expiry_date", nullable = false)
    private Instant expiryDate;

    @Column(name = "is_used", nullable = false)
    private boolean used;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private UserEntity user;

    public boolean isExpired() {
        return isExpiredAt(Instant.now());
    }

    public boolean isExpiredAt(Instant now) {
        return expiryDate == null || !expiryDate.isAfter(now);
    }

    public boolean isConsumedOrInvalidated() {
        return used || consumedAt != null || invalidatedAt != null;
    }
}
