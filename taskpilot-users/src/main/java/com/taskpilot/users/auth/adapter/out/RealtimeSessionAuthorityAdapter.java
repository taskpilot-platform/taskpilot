package com.taskpilot.users.auth.adapter.out;

import com.taskpilot.contracts.realtime.RealtimeAccessDecision;
import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.SessionAuthStateDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.RealtimeSessionAuthorityPort;
import com.taskpilot.contracts.user.port.out.RealtimeTokenBlocklistPort;
import com.taskpilot.contracts.user.port.out.SessionAuthStatePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Authoritative users-owned realtime session and account evaluator.
 * Supports non-locking reads and canonical user -> session pessimistic-locked checks.
 */
@Component
public class RealtimeSessionAuthorityAdapter implements RealtimeSessionAuthorityPort {

    private static final String ACTIVE_LIFECYCLE = "ACTIVE";

    private final AccountAuthStatePort accountAuthStatePort;
    private final SessionAuthStatePort sessionAuthStatePort;
    private final RealtimeTokenBlocklistPort tokenBlocklistPort;
    private final Clock clock;

    @Autowired
    public RealtimeSessionAuthorityAdapter(
            AccountAuthStatePort accountAuthStatePort,
            SessionAuthStatePort sessionAuthStatePort,
            ObjectProvider<RealtimeTokenBlocklistPort> tokenBlocklistPortProvider
    ) {
        this(
                accountAuthStatePort,
                sessionAuthStatePort,
                tokenBlocklistPortProvider != null ? tokenBlocklistPortProvider.getIfAvailable() : null,
                Clock.systemUTC()
        );
    }

    public RealtimeSessionAuthorityAdapter(
            AccountAuthStatePort accountAuthStatePort,
            SessionAuthStatePort sessionAuthStatePort,
            RealtimeTokenBlocklistPort tokenBlocklistPort,
            Clock clock
    ) {
        this.accountAuthStatePort = Objects.requireNonNull(accountAuthStatePort, "accountAuthStatePort must not be null");
        this.sessionAuthStatePort = Objects.requireNonNull(sessionAuthStatePort, "sessionAuthStatePort must not be null");
        this.tokenBlocklistPort = tokenBlocklistPort;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSessionCurrent(RealtimeAccessContext context) {
        return evaluateSession(context).allowed();
    }

    @Override
    @Transactional(readOnly = true)
    public RealtimeAccessDecision evaluateSession(RealtimeAccessContext context) {
        return evaluateInternal(context, false);
    }

    @Override
    @Transactional
    public RealtimeAccessDecision evaluateSessionForUpdate(RealtimeAccessContext context) {
        return evaluateInternal(context, true);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RealtimeAccessDecision evaluateSessionInNewTx(RealtimeAccessContext context) {
        return evaluateInternal(context, true);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean isAccountActiveInNewTx(long userId) {
        if (userId <= 0) {
            return false;
        }
        return accountAuthStatePort.findByIdForUpdate(userId)
                .map(account -> ACTIVE_LIFECYCLE.equals(account.lifecycle()) && account.emailVerified())
                .orElse(false);
    }

    private RealtimeAccessDecision evaluateInternal(RealtimeAccessContext context, boolean lockRows) {
        if (context == null
                || context.userId() <= 0
                || context.sessionId() == null
                || context.tokenId() == null
                || context.tokenId().isBlank()
                || context.expiresAt() == null) {
            return RealtimeAccessDecision.deny(
                    RealtimeAccessDenyReason.UNAUTHENTICATED,
                    "Missing realtime session context"
            );
        }

        Instant now = clock.instant();
        if (!context.expiresAt().isAfter(now)) {
            return RealtimeAccessDecision.deny(
                    RealtimeAccessDenyReason.AUTH_EXPIRED,
                    "Access token expired"
            );
        }

        if (tokenBlocklistPort != null && tokenBlocklistPort.isTokenRevoked(context.tokenId())) {
            return RealtimeAccessDecision.deny(
                    RealtimeAccessDenyReason.SESSION_REVOKED,
                    "Access token revoked"
            );
        }

        AccountAuthStateDto account = (lockRows
                ? accountAuthStatePort.findByIdForUpdate(context.userId())
                : accountAuthStatePort.findById(context.userId()))
                .orElse(null);
        if (account == null) {
            return RealtimeAccessDecision.deny(
                    RealtimeAccessDenyReason.ACCOUNT_INACTIVE,
                    "Account unavailable"
            );
        }
        if (!ACTIVE_LIFECYCLE.equals(account.lifecycle())
                || !account.emailVerified()
                || account.role() == null
                || account.role().isBlank()) {
            return RealtimeAccessDecision.deny(
                    RealtimeAccessDenyReason.ACCOUNT_INACTIVE,
                    "Account inactive"
            );
        }
        if (account.tokenVersion() != context.authVersion()) {
            return RealtimeAccessDecision.deny(
                    RealtimeAccessDenyReason.SESSION_REVOKED,
                    "Security version invalidated"
            );
        }

        SessionAuthStateDto session = (lockRows
                ? sessionAuthStatePort.findByIdForUpdate(context.sessionId())
                : sessionAuthStatePort.findById(context.sessionId()))
                .orElse(null);
        if (session == null
                || session.revoked()
                || session.userId() != context.userId()
                || session.tokenVersion() != context.authVersion()) {
            return RealtimeAccessDecision.deny(
                    RealtimeAccessDenyReason.SESSION_REVOKED,
                    "Session revoked"
            );
        }
        // Lock acquisition can outlive the token/session deadline.
        now = clock.instant();
        if (!context.expiresAt().isAfter(now) || session.expiresAt() == null || !session.expiresAt().isAfter(now)) {
            return RealtimeAccessDecision.deny(
                    RealtimeAccessDenyReason.AUTH_EXPIRED,
                    "Session expired"
            );
        }

        return RealtimeAccessDecision.allow();
    }
}
