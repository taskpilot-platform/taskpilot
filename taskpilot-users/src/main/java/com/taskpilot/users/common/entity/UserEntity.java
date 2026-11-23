package com.taskpilot.users.common.entity;

import com.taskpilot.infrastructure.base.BaseEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "users_id_seq_gen")
    @SequenceGenerator(name = "users_id_seq_gen", sequenceName = "users_id_seq", allocationSize = 1)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash")
    private String password;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    private UserRole role;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    private UserStatus status = UserStatus.AVAILABLE;

    @Builder.Default
    @Column(name = "current_workload")
    private Integer currentWorkload = 0;

    @Builder.Default
    @Column(name = "token_version", nullable = false)
    private Long tokenVersion = 0L;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "account_state", nullable = false, length = 32)
    private AccountLifecycle accountState = AccountLifecycle.ACTIVE;

    @Builder.Default
    @Column(name = "email_verified", nullable = false)
    private Boolean emailVerified = Boolean.FALSE;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "email_verification_provenance", length = 64)
    private String emailVerificationProvenance;

    @Column(name = "lifecycle_changed_at")
    private Instant lifecycleChangedAt;

    @Column(name = "lifecycle_reason", length = 255)
    private String lifecycleReason;

    @Column(name = "lifecycle_changed_by")
    private Long lifecycleChangedBy;

    @Builder.Default
    @Column(name = "legacy_review_required", nullable = false)
    private Boolean legacyReviewRequired = Boolean.FALSE;

    public boolean isActiveAccount() {
        AccountLifecycle effectiveLifecycle = accountState != null ? accountState : AccountLifecycle.ACTIVE;
        return effectiveLifecycle == AccountLifecycle.ACTIVE && status != UserStatus.DEACTIVATED;
    }

    public boolean hasUsablePassword() {
        return password != null && !password.isBlank();
    }

    public UserStatus effectiveAvailability() {
        if (status == null || status == UserStatus.DEACTIVATED) {
            return UserStatus.AVAILABLE;
        }
        return status;
    }

    public long effectiveTokenVersion() {
        return tokenVersion != null ? tokenVersion : 0L;
    }

    public long incrementTokenVersion() {
        long next = effectiveTokenVersion() + 1L;
        this.tokenVersion = next;
        return next;
    }
}
