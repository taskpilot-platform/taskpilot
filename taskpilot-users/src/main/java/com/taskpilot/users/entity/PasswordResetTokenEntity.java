package com.taskpilot.users.entity;

import com.taskpilot.infrastructure.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
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

   @Column(nullable = false, unique = true)
   private String token;

   @Column(name = "expiry_date", nullable = false)
   private Instant expiryDate;

   @Column(name = "is_used", nullable = false)
   private boolean used;

   @OneToOne(fetch = FetchType.LAZY)
   @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
   private UserEntity user;

   public boolean isExpired() {
      return expiryDate.isBefore(Instant.now());
   }
}
