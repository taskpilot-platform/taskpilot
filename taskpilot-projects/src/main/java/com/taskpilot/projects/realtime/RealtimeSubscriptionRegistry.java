package com.taskpilot.projects.realtime;

import com.taskpilot.contracts.project.dto.ProjectAccessRevokedEvent;
import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import com.taskpilot.contracts.realtime.RealtimeAccessPort;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.UserSecurityChangedEvent;
import com.taskpilot.contracts.user.dto.UserSessionRevokedEvent;
import com.taskpilot.projects.chat.config.StompWebSocketSessionTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded per-user STOMP connection and subscription registry.
 * Drives idle expiry, per-session logout, user security epoch revocation, and project revocation cleanup.
 */
@Slf4j
@Service
public class RealtimeSubscriptionRegistry {

    public static final int DEFAULT_MAX_SESSIONS_PER_USER = 16;
    public static final int DEFAULT_MAX_SUBSCRIPTIONS_PER_SESSION = 32;
    private static final long EXPIRY_SWEEP_INTERVAL_MILLIS = 5_000L;

    private final StompWebSocketSessionTracker sessionTracker;
    private final RealtimeAccessPort realtimeAccessPort;
    private final Clock clock;
    private final int maxSessionsPerUser;
    private final int maxSubscriptionsPerSession;

    private final Map<String, RegisteredStompSession> sessionsBySimpId = new ConcurrentHashMap<>();
    private final Map<Long, CopyOnWriteArrayList<String>> simpIdsByUser = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "stomp-expiry-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    @Autowired
    public RealtimeSubscriptionRegistry(
            StompWebSocketSessionTracker sessionTracker,
            RealtimeAccessPort realtimeAccessPort
    ) {
        this(
                sessionTracker,
                realtimeAccessPort,
                Clock.systemUTC(),
                DEFAULT_MAX_SESSIONS_PER_USER,
                DEFAULT_MAX_SUBSCRIPTIONS_PER_SESSION
        );
    }

    public RealtimeSubscriptionRegistry(
            StompWebSocketSessionTracker sessionTracker,
            RealtimeAccessPort realtimeAccessPort,
            Clock clock,
            int maxSessionsPerUser,
            int maxSubscriptionsPerSession
    ) {
        this.sessionTracker = sessionTracker;
        this.realtimeAccessPort = realtimeAccessPort;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.maxSessionsPerUser = Math.max(1, maxSessionsPerUser);
        this.maxSubscriptionsPerSession = Math.max(1, maxSubscriptionsPerSession);
    }

    @PostConstruct
    void startExpirySweep() {
        expiryScheduler.scheduleAtFixedRate(
                this::sweepExpired,
                EXPIRY_SWEEP_INTERVAL_MILLIS,
                EXPIRY_SWEEP_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    void shutdown() {
        expiryScheduler.shutdownNow();
    }

    public void registerSession(String simpSessionId, RealtimeAccessContext context) {
        if (simpSessionId == null || simpSessionId.isBlank() || context == null) {
            return;
        }
        RegisteredStompSession previous = sessionsBySimpId.remove(simpSessionId);
        if (previous != null) {
            previous.cancelExpiryFuture();
            detachFromUserIndex(previous.context().userId(), simpSessionId);
        }

        RegisteredStompSession session = new RegisteredStompSession(simpSessionId, context);
        CopyOnWriteArrayList<String> userSessions =
                simpIdsByUser.computeIfAbsent(context.userId(), ignored -> new CopyOnWriteArrayList<>());
        synchronized (userSessions) {
            while (userSessions.size() >= maxSessionsPerUser) {
                String oldestSimpId = userSessions.get(0);
                closeAndRemove(oldestSimpId, RealtimeAccessDenyReason.SESSION_REVOKED);
            }
            userSessions.add(simpSessionId);
        }
        sessionsBySimpId.put(simpSessionId, session);

        long delayMs = Math.max(0L, Duration.between(clock.instant(), context.expiresAt()).toMillis());
        if (!expiryScheduler.isShutdown()) {
            ScheduledFuture<?> future = expiryScheduler.schedule(
                    () -> expireSessionIfDeadlineReached(simpSessionId),
                    delayMs,
                    TimeUnit.MILLISECONDS
            );
            session.setExpiryFuture(future);
        }
    }

    public void registerSubscription(String simpSessionId, String subscriptionId, long projectId) {
        if (simpSessionId == null || subscriptionId == null || subscriptionId.isBlank() || projectId <= 0) {
            return;
        }
        RegisteredStompSession session = sessionsBySimpId.get(simpSessionId);
        if (session == null || session.closed().get()) {
            return;
        }
        synchronized (session.subscriptions()) {
            if (!session.subscriptions().containsKey(subscriptionId)
                    && session.subscriptions().size() >= maxSubscriptionsPerSession) {
                String oldestSubId = session.subscriptions().keySet().iterator().next();
                session.subscriptions().remove(oldestSubId);
            }
            session.subscriptions().put(subscriptionId, projectId);
        }
    }

    public void removeSubscription(String simpSessionId, String subscriptionId) {
        if (simpSessionId == null || subscriptionId == null) {
            return;
        }
        RegisteredStompSession session = sessionsBySimpId.get(simpSessionId);
        if (session != null) {
            synchronized (session.subscriptions()) {
                session.subscriptions().remove(subscriptionId);
            }
        }
    }

    public void removeSession(String simpSessionId) {
        if (simpSessionId == null) {
            return;
        }
        RegisteredStompSession removed = sessionsBySimpId.remove(simpSessionId);
        if (removed != null) {
            removed.closed().set(true);
            removed.cancelExpiryFuture();
            detachFromUserIndex(removed.context().userId(), simpSessionId);
        }
    }

    public RegisteredSubscription lookupSubscription(String simpSessionId, String subscriptionId, long projectId) {
        if (simpSessionId == null || subscriptionId == null) {
            return null;
        }
        RegisteredStompSession session = sessionsBySimpId.get(simpSessionId);
        if (session == null || session.closed().get()) {
            return null;
        }
        Long boundProjectId;
        synchronized (session.subscriptions()) {
            boundProjectId = session.subscriptions().get(subscriptionId);
        }
        if (boundProjectId == null || boundProjectId != projectId) {
            return null;
        }
        return new RegisteredSubscription(simpSessionId, subscriptionId, boundProjectId, session.context());
    }

    public void closeAndRemove(String simpSessionId, RealtimeAccessDenyReason reason) {
        if (simpSessionId == null) {
            return;
        }
        RegisteredStompSession removed = sessionsBySimpId.remove(simpSessionId);
        if (removed != null) {
            if (!removed.closed().compareAndSet(false, true)) {
                return;
            }
            removed.cancelExpiryFuture();
            detachFromUserIndex(removed.context().userId(), simpSessionId);
        }
        if (sessionTracker != null) {
            sessionTracker.closeSession(simpSessionId, reason);
        }
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        if (event != null) {
            removeSession(event.getSessionId());
        }
    }

    @EventListener
    public void onUserSessionRevoked(UserSessionRevokedEvent event) {
        if (event == null || event.sessionId() == null) {
            return;
        }
        revokeSession(event.sessionId());
    }

    @EventListener
    public void onUserSecurityChanged(UserSecurityChangedEvent event) {
        if (event == null) {
            return;
        }
        revokeUser(event.userId(), event.tokenVersion());
    }

    @EventListener
    public void onProjectAccessRevoked(ProjectAccessRevokedEvent event) {
        if (event == null) {
            return;
        }
        revokeProjectUser(event.projectId(), event.userId());
    }

    public void revokeSession(UUID authSessionId) {
        if (authSessionId == null) {
            return;
        }
        for (RegisteredStompSession session : List.copyOf(sessionsBySimpId.values())) {
            if (authSessionId.equals(session.context().sessionId())) {
                closeAndRemove(session.simpSessionId(), RealtimeAccessDenyReason.SESSION_REVOKED);
            }
        }
    }

    public void revokeUser(long userId, long minTokenVersion) {
        List<String> simpIds = simpIdsByUser.get(userId);
        if (simpIds == null || simpIds.isEmpty()) {
            return;
        }
        for (String simpId : List.copyOf(simpIds)) {
            RegisteredStompSession session = sessionsBySimpId.get(simpId);
            if (session == null) {
                continue;
            }
            if (session.context().authVersion() < minTokenVersion
                    || (realtimeAccessPort != null && !realtimeAccessPort.isSessionCurrent(session.context()))) {
                closeAndRemove(simpId, RealtimeAccessDenyReason.SESSION_REVOKED);
            }
        }
    }

    public void revokeProjectUser(long projectId, long userId) {
        List<String> simpIds = simpIdsByUser.get(userId);
        if (simpIds == null || simpIds.isEmpty()) {
            return;
        }
        for (String simpId : List.copyOf(simpIds)) {
            RegisteredStompSession session = sessionsBySimpId.get(simpId);
            if (session == null) {
                continue;
            }
            boolean hadProjectSubscription;
            boolean remainingSubscriptionsEmpty;
            synchronized (session.subscriptions()) {
                hadProjectSubscription = session.subscriptions().values().removeIf(pid -> pid == projectId);
                remainingSubscriptionsEmpty = session.subscriptions().isEmpty();
            }
            if (hadProjectSubscription && remainingSubscriptionsEmpty) {
                closeAndRemove(simpId, RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED);
            }
        }
    }

    public void sweepExpired() {
        Instant now = clock.instant();
        for (RegisteredStompSession session : List.copyOf(sessionsBySimpId.values())) {
            if (!session.context().expiresAt().isAfter(now)) {
                closeAndRemove(session.simpSessionId(), RealtimeAccessDenyReason.AUTH_EXPIRED);
            } else if (realtimeAccessPort != null) {
                var decision = realtimeAccessPort.evaluateSession(session.context());
                if (!decision.allowed()) {
                    closeAndRemove(session.simpSessionId(), decision.denyReason());
                }
            }
        }
    }

    public int activeSessionCountForUser(long userId) {
        List<String> list = simpIdsByUser.get(userId);
        return list != null ? list.size() : 0;
    }

    public Set<String> activeSimpSessionIds() {
        return Set.copyOf(sessionsBySimpId.keySet());
    }

    private void expireSessionIfDeadlineReached(String simpSessionId) {
        RegisteredStompSession session = sessionsBySimpId.get(simpSessionId);
        if (session == null || session.closed().get()) {
            return;
        }
        if (!session.context().expiresAt().isAfter(clock.instant())) {
            closeAndRemove(simpSessionId, RealtimeAccessDenyReason.AUTH_EXPIRED);
        } else if (realtimeAccessPort != null) {
            var decision = realtimeAccessPort.evaluateSession(session.context());
            if (!decision.allowed()) {
                closeAndRemove(simpSessionId, decision.denyReason());
            }
        }
    }

    private void detachFromUserIndex(long userId, String simpSessionId) {
        CopyOnWriteArrayList<String> userSessions = simpIdsByUser.get(userId);
        if (userSessions != null) {
            userSessions.remove(simpSessionId);
            if (userSessions.isEmpty()) {
                simpIdsByUser.remove(userId, userSessions);
            }
        }
    }

    public record RegisteredSubscription(
            String simpSessionId,
            String subscriptionId,
            long projectId,
            RealtimeAccessContext context
    ) {
    }

    private static final class RegisteredStompSession {
        private final String simpSessionId;
        private final RealtimeAccessContext context;
        private final Map<String, Long> subscriptions = new java.util.LinkedHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> expiryFuture;

        private RegisteredStompSession(String simpSessionId, RealtimeAccessContext context) {
            this.simpSessionId = simpSessionId;
            this.context = context;
        }

        private String simpSessionId() {
            return simpSessionId;
        }

        private RealtimeAccessContext context() {
            return context;
        }

        private Map<String, Long> subscriptions() {
            return subscriptions;
        }

        private AtomicBoolean closed() {
            return closed;
        }

        private void setExpiryFuture(ScheduledFuture<?> expiryFuture) {
            this.expiryFuture = expiryFuture;
            if (closed.get() && expiryFuture != null) {
                expiryFuture.cancel(false);
            }
        }

        private void cancelExpiryFuture() {
            ScheduledFuture<?> future = this.expiryFuture;
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
