package com.taskpilot.users.auth.service;

import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.AuthResponse;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.EmailTokenPurpose;
import com.taskpilot.users.common.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Manages single-use 5-minute purpose-bound RESTORE tickets for SELF_DEACTIVATED accounts.
 * Admin-suspended and legacy-disabled accounts are strictly excluded from self-service restore.
 */
@Service
@RequiredArgsConstructor
public class RestoreTicketService {

    public static final long RESTORE_TICKET_TTL_SECONDS = 300L;
    private static final long RESTORE_TICKET_TTL_MS = RESTORE_TICKET_TTL_SECONDS * 1000L;

    private final EmailChallengeService emailChallengeService;
    private final UserSessionService userSessionService;

    @Transactional
    public EmailChallengeService.IssuedChallenge issueRestoreTicket(UserEntity lockedSelfDeactivatedUser) {
        if (lockedSelfDeactivatedUser.getAccountState() != AccountLifecycle.SELF_DEACTIVATED) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Account is not eligible for self-service restoration");
        }
        return emailChallengeService.issueForLockedUser(
                lockedSelfDeactivatedUser,
                EmailTokenPurpose.RESTORE,
                RESTORE_TICKET_TTL_MS,
                null);
    }

    @Transactional
    public AuthResponse confirmRestore(String rawTicket) {
        EmailChallengeService.ConsumedChallenge consumed = emailChallengeService.consume(
                rawTicket,
                EmailTokenPurpose.RESTORE,
                null,
                null,
                user -> {
                    if (user.getAccountState() == AccountLifecycle.ADMIN_SUSPENDED
                            || user.getAccountState() == AccountLifecycle.LEGACY_DISABLED) {
                        throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                                "Account restoration is restricted. Please contact an administrator.");
                    }
                    if (user.getAccountState() != AccountLifecycle.SELF_DEACTIVATED) {
                        throw new BusinessException(HttpStatus.CONFLICT.value(),
                                "Account is not in a self-deactivated state");
                    }
                    if (!Boolean.TRUE.equals(user.getEmailVerified())) {
                        throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                                "Email verification is required before restoring account");
                    }
                    user.setAccountState(AccountLifecycle.ACTIVE);
                    if (user.getStatus() == null || user.getStatus() == UserStatus.DEACTIVATED) {
                        user.setStatus(UserStatus.AVAILABLE);
                    }
                    user.setLifecycleChangedAt(Instant.now());
                    user.setLifecycleReason("SELF_RESTORED");
                    user.setLifecycleChangedBy(user.getId());
                });

        userSessionService.invalidateAll(consumed.userId(), SecurityChangeReason.USER_RESTORED);
        return userSessionService.issueSession(consumed.userId());
    }

    @Transactional
    public void cancelRestore(String rawTicket) {
        emailChallengeService.consume(
                rawTicket,
                EmailTokenPurpose.RESTORE,
                null,
                null,
                user -> {
                    // Intentionally leave account state unchanged; ticket is consumed and discarded.
                });
    }
}
