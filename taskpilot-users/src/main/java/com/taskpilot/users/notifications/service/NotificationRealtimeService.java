package com.taskpilot.users.notifications.service;

import com.taskpilot.contracts.project.port.out.ProjectDeliveryAuthorizationPort;
import com.taskpilot.contracts.realtime.RealtimeAccessDecision;
import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.UserSecurityChangedEvent;
import com.taskpilot.contracts.user.dto.UserSessionRevokedEvent;
import com.taskpilot.contracts.user.port.out.RealtimeSessionAuthorityPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.realtime.RealtimeSseSupport;
import com.taskpilot.users.notifications.dto.NotificationResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class NotificationRealtimeService {

    private static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 25_000L;
    private static final long EXPIRY_SWEEP_INTERVAL_MILLIS = 5_000L;

    private final RealtimeSessionAuthorityPort sessionAuthorityPort;
    private final ObjectProvider<ProjectDeliveryAuthorizationPort> projectDeliveryAuthProvider;
    private final Clock clock;
    private final int maxEmittersPerUser;

    private final Map<Long, CopyOnWriteArrayList<EmitterRegistration>> emittersByUser =
            new ConcurrentHashMap<>();
    private final Map<UUID, CopyOnWriteArrayList<EmitterRegistration>> emittersBySession =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "notification-sse-realtime");
                thread.setDaemon(true);
                return thread;
            });

    @Autowired
    public NotificationRealtimeService(
            RealtimeSessionAuthorityPort sessionAuthorityPort,
            ObjectProvider<ProjectDeliveryAuthorizationPort> projectDeliveryAuthProvider
    ) {
        this(
                sessionAuthorityPort,
                projectDeliveryAuthProvider,
                Clock.systemUTC(),
                RealtimeSseSupport.DEFAULT_MAX_EMITTERS_PER_USER
        );
    }

    public NotificationRealtimeService(
            RealtimeSessionAuthorityPort sessionAuthorityPort,
            ObjectProvider<ProjectDeliveryAuthorizationPort> projectDeliveryAuthProvider,
            Clock clock,
            int maxEmittersPerUser
    ) {
        this.sessionAuthorityPort = sessionAuthorityPort;
        this.projectDeliveryAuthProvider = projectDeliveryAuthProvider;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.maxEmittersPerUser = Math.max(1, maxEmittersPerUser);
    }

    @PostConstruct
    void startLoops() {
        scheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                HEARTBEAT_INTERVAL_MILLIS,
                HEARTBEAT_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
        scheduler.scheduleAtFixedRate(
                this::sweepExpired,
                EXPIRY_SWEEP_INTERVAL_MILLIS,
                EXPIRY_SWEEP_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    void stopLoops() {
        scheduler.shutdownNow();
    }

    public SseEmitter subscribe(RealtimeAccessContext context, long unreadCount) {
        RealtimeAccessDecision decision = evaluateSession(context);
        if (!decision.allowed()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(),
                    decision.detail() != null ? decision.detail() : "Authentication required");
        }

        long userId = context.userId();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        EmitterRegistration registration = new EmitterRegistration("notif-" + UUID.randomUUID(), context, emitter);

        CopyOnWriteArrayList<EmitterRegistration> userEmitters =
                emittersByUser.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>());
        synchronized (userEmitters) {
            while (userEmitters.size() >= maxEmittersPerUser) {
                EmitterRegistration evicted = userEmitters.get(0);
                remove(registrationOrOldest(evicted), "capacity-evicted", null, true, null);
            }
            userEmitters.add(registration);
        }
        emittersBySession
                .computeIfAbsent(context.sessionId(), ignored -> new CopyOnWriteArrayList<>())
                .add(registration);

        long delayMs = Math.max(0L, Duration.between(clock.instant(), context.expiresAt()).toMillis());
        if (!scheduler.isShutdown()) {
            ScheduledFuture<?> expiryFuture = scheduler.schedule(
                    () -> expireIfDeadlineReached(registration),
                    delayMs,
                    TimeUnit.MILLISECONDS
            );
            registration.setExpiryFuture(expiryFuture);
        }

        log.debug("Notification SSE opened userId={} sessionId={} emitterId={} active={}",
                userId, context.sessionId(), registration.id(), userEmitters.size());

        emitter.onCompletion(() -> {
            log.debug("Notification SSE completed userId={} emitterId={}", userId, registration.id());
            remove(registration, "completion", null, false, null);
        });
        emitter.onTimeout(() -> {
            log.warn("Notification SSE timed out userId={} emitterId={}", userId, registration.id());
            remove(registration, "timeout", null, true, null);
        });
        emitter.onError(error -> {
            if (RealtimeSseSupport.isClientAbort(error)) {
                log.debug("Notification SSE client abort userId={} emitterId={} reason={}",
                        userId, registration.id(), error.getMessage());
            } else {
                log.warn("Notification SSE error userId={} emitterId={} reason={}",
                        userId, registration.id(), error.getMessage());
            }
            remove(registration, "error", error, false, null);
        });

        safeSendJson(registration, "notification.unread-count", unreadCount, "initial", null);
        return emitter;
    }

    public void publishCreated(NotificationResponse notification, long unreadCount) {
        publishCreated(notification, unreadCount, null);
    }

    public void publishCreated(NotificationResponse notification, long unreadCount, Long projectId) {
        if (notification == null || notification.userId() == null) {
            return;
        }
        List<EmitterRegistration> emitters = emittersByUser.get(notification.userId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (EmitterRegistration registration : emitters) {
            if (safeSendJson(registration, "notification.created", notification, "publish", projectId)) {
                safeSendJson(registration, "notification.unread-count", unreadCount, "publish", projectId);
            }
        }
    }

    public void publish(Long userId, String eventName, Object payload) {
        if (userId == null) {
            return;
        }
        List<EmitterRegistration> emitters = emittersByUser.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (EmitterRegistration registration : emitters) {
            safeSendJson(registration, eventName, payload, "publish", null);
        }
    }

    @EventListener
    public void onUserSessionRevoked(UserSessionRevokedEvent event) {
        if (event == null || event.sessionId() == null) {
            return;
        }
        List<EmitterRegistration> sessionEmitters = emittersBySession.get(event.sessionId());
        if (sessionEmitters == null || sessionEmitters.isEmpty()) {
            return;
        }
        for (EmitterRegistration registration : List.copyOf(sessionEmitters)) {
            remove(registration, "session-revoked", null, true, RealtimeAccessDenyReason.SESSION_REVOKED);
        }
    }

    @EventListener
    public void onUserSecurityChanged(UserSecurityChangedEvent event) {
        if (event == null) {
            return;
        }
        List<EmitterRegistration> userEmitters = emittersByUser.get(event.userId());
        if (userEmitters == null || userEmitters.isEmpty()) {
            return;
        }
        for (EmitterRegistration registration : List.copyOf(userEmitters)) {
            if (registration.context().authVersion() < event.tokenVersion()
                    || !evaluateSession(registration.context()).allowed()) {
                remove(registration, "security-changed", null, true, RealtimeAccessDenyReason.SESSION_REVOKED);
            }
        }
    }

    public void sendHeartbeats() {
        try {
            emittersByUser.forEach((userId, emitters) -> {
                for (EmitterRegistration registration : emitters) {
                    safeSendHeartbeat(registration);
                }
            });
        } catch (Exception ex) {
            log.warn("Notification SSE heartbeat loop failed: {}", ex.getMessage());
        }
    }

    public void sweepExpired() {
        Instant now = clock.instant();
        emittersByUser.forEach((userId, emitters) -> {
            for (EmitterRegistration registration : List.copyOf(emitters)) {
                if (!registration.context().expiresAt().isAfter(now)) {
                    remove(registration, "expired", null, true, RealtimeAccessDenyReason.AUTH_EXPIRED);
                } else {
                    RealtimeAccessDecision decision = evaluateSession(registration.context());
                    if (!decision.allowed()) {
                        remove(registration, "authority-revoked", null, true, decision.denyReason());
                    }
                }
            }
        });
    }

    public int activeCountForUser(long userId) {
        List<EmitterRegistration> list = emittersByUser.get(userId);
        return list != null ? list.size() : 0;
    }

    public int activeCountForSession(UUID sessionId) {
        List<EmitterRegistration> list = emittersBySession.get(sessionId);
        return list != null ? list.size() : 0;
    }

    private void expireIfDeadlineReached(EmitterRegistration registration) {
        if (registration.removed().get()) {
            return;
        }
        if (!registration.context().expiresAt().isAfter(clock.instant())) {
            remove(registration, "expired-timer", null, true, RealtimeAccessDenyReason.AUTH_EXPIRED);
        } else {
            RealtimeAccessDecision decision = evaluateSession(registration.context());
            if (!decision.allowed()) {
                remove(registration, "authority-revoked-timer", null, true, decision.denyReason());
            }
        }
    }

    private RealtimeAccessDecision evaluateSession(RealtimeAccessContext context) {
        if (sessionAuthorityPort == null) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.SESSION_REVOKED, "Session authority unavailable");
        }
        RealtimeAccessDecision decision = sessionAuthorityPort.evaluateSessionInNewTx(context);
        if (decision == null) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.SESSION_REVOKED, "Session authority denied");
        }
        if (decision.allowed() && !context.expiresAt().isAfter(clock.instant())) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.AUTH_EXPIRED, "Access token expired");
        }
        return decision;
    }

    private boolean isProjectDeliveryAllowed(Long projectId, long userId) {
        if (projectId == null) {
            return true;
        }
        ProjectDeliveryAuthorizationPort port = projectDeliveryAuthProvider != null
                ? projectDeliveryAuthProvider.getIfAvailable()
                : null;
        return port != null && port.canDeliverProjectNotification(projectId, null, userId);
    }

    private boolean safeSendJson(
            EmitterRegistration registration,
            String eventName,
            Object payload,
            String source,
            Long projectId
    ) {
        if (registration.removed().get()) {
            return false;
        }
        RealtimeAccessDecision decision = evaluateSession(registration.context());
        if (!decision.allowed()) {
            remove(registration, "unauthorized-" + source, null, true, decision.denyReason());
            return false;
        }
        if (!isProjectDeliveryAllowed(projectId, registration.context().userId())) {
            return false;
        }
        try {
            registration.emitter().send(
                    SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Notification SSE send failed userId={} emitterId={} source={} event={} reason={}",
                    registration.context().userId(), registration.id(), source, eventName, ex.getMessage());
            remove(registration, "send-failed", ex, false, null);
            return false;
        }
    }

    private boolean safeSendHeartbeat(EmitterRegistration registration) {
        if (registration.removed().get()) {
            return false;
        }
        RealtimeAccessDecision decision = evaluateSession(registration.context());
        if (!decision.allowed()) {
            remove(registration, "unauthorized-heartbeat", null, true, decision.denyReason());
            return false;
        }
        try {
            registration.emitter().send(SseEmitter.event().comment("keep-alive"));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Notification SSE heartbeat failed userId={} emitterId={} reason={}",
                    registration.context().userId(), registration.id(), ex.getMessage());
            remove(registration, "heartbeat-failed", ex, false, null);
            return false;
        }
    }

    private static EmitterRegistration registrationOrOldest(EmitterRegistration reg) {
        return reg;
    }

    private void remove(
            EmitterRegistration registration,
            String reason,
            Throwable error,
            boolean completeEmitter,
            RealtimeAccessDenyReason accessLostReason
    ) {
        if (!registration.removed().compareAndSet(false, true)) {
            return;
        }

        registration.cancelExpiryFuture();

        if (completeEmitter) {
            if (accessLostReason != null) {
                RealtimeSseSupport.sendAccessLostAndComplete(
                        registration.emitter(), registration.completed(), accessLostReason, reason);
            } else {
                RealtimeSseSupport.completeOnce(registration.emitter(), registration.completed());
            }
        }

        long userId = registration.context().userId();
        UUID sessionId = registration.context().sessionId();

        CopyOnWriteArrayList<EmitterRegistration> userEmitters = emittersByUser.get(userId);
        if (userEmitters != null) {
            userEmitters.remove(registration);
            if (userEmitters.isEmpty()) {
                emittersByUser.remove(userId, userEmitters);
            }
        }

        CopyOnWriteArrayList<EmitterRegistration> sessionEmitters = emittersBySession.get(sessionId);
        if (sessionEmitters != null) {
            sessionEmitters.remove(registration);
            if (sessionEmitters.isEmpty()) {
                emittersBySession.remove(sessionId, sessionEmitters);
            }
        }

        String message = error != null ? error.getMessage() : "-";
        log.debug("Notification SSE removed userId={} sessionId={} emitterId={} reason={} detail={}",
                userId, sessionId, registration.id(), reason, message);
    }

    private static final class EmitterRegistration {
        private final String id;
        private final RealtimeAccessContext context;
        private final SseEmitter emitter;
        private final AtomicBoolean removed = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> expiryFuture;

        private EmitterRegistration(String id, RealtimeAccessContext context, SseEmitter emitter) {
            this.id = id;
            this.context = context;
            this.emitter = emitter;
        }

        private String id() {
            return id;
        }

        private RealtimeAccessContext context() {
            return context;
        }

        private SseEmitter emitter() {
            return emitter;
        }

        private AtomicBoolean removed() {
            return removed;
        }

        private AtomicBoolean completed() {
            return completed;
        }

        private void setExpiryFuture(ScheduledFuture<?> expiryFuture) {
            this.expiryFuture = expiryFuture;
            if (removed.get() && expiryFuture != null) {
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
