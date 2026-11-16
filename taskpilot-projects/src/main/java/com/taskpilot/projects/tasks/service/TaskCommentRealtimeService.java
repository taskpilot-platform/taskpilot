package com.taskpilot.projects.tasks.service;

import com.taskpilot.contracts.project.dto.ProjectAccessRevokedEvent;
import com.taskpilot.contracts.realtime.RealtimeAccessDecision;
import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import com.taskpilot.contracts.realtime.RealtimeAccessPort;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.UserSecurityChangedEvent;
import com.taskpilot.contracts.user.dto.UserSessionRevokedEvent;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.realtime.RealtimeSseSupport;
import com.taskpilot.projects.tasks.dto.TaskCommentDeletedEvent;
import com.taskpilot.projects.tasks.dto.TaskCommentDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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
public class TaskCommentRealtimeService {

    private static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 25_000L;
    private static final long EXPIRY_SWEEP_INTERVAL_MILLIS = 5_000L;

    private final RealtimeAccessPort realtimeAccessPort;
    private final Clock clock;
    private final int maxEmittersPerUser;

    private final Map<Long, CopyOnWriteArrayList<EmitterRegistration>> emittersByTask = new ConcurrentHashMap<>();
    private final Map<Long, CopyOnWriteArrayList<EmitterRegistration>> emittersByUser = new ConcurrentHashMap<>();
    private final Map<UUID, CopyOnWriteArrayList<EmitterRegistration>> emittersBySession = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "comment-sse-realtime");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public TaskCommentRealtimeService(RealtimeAccessPort realtimeAccessPort) {
        this(realtimeAccessPort, Clock.systemUTC(), RealtimeSseSupport.DEFAULT_MAX_EMITTERS_PER_USER);
    }

    public TaskCommentRealtimeService(
            RealtimeAccessPort realtimeAccessPort,
            Clock clock,
            int maxEmittersPerUser
    ) {
        this.realtimeAccessPort = realtimeAccessPort;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.maxEmittersPerUser = Math.max(1, maxEmittersPerUser);
    }

    @PostConstruct
    void startLoops() {
        scheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                HEARTBEAT_INTERVAL_MILLIS,
                HEARTBEAT_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(
                this::sweepExpired,
                EXPIRY_SWEEP_INTERVAL_MILLIS,
                EXPIRY_SWEEP_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopLoops() {
        scheduler.shutdownNow();
    }

    public SseEmitter subscribe(long projectId, long taskId, RealtimeAccessContext context) {
        RealtimeAccessDecision decision = evaluateAccess(projectId, context);
        if (!decision.allowed()) {
            int status = decision.denyReason() == RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED
                    ? HttpStatus.FORBIDDEN.value()
                    : HttpStatus.UNAUTHORIZED.value();
            throw new BusinessException(status, decision.detail() != null ? decision.detail() : "Access denied");
        }

        long userId = context.userId();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        EmitterRegistration registration = new EmitterRegistration(
                "comment-" + UUID.randomUUID(), projectId, taskId, context, emitter);

        CopyOnWriteArrayList<EmitterRegistration> userEmitters =
                emittersByUser.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>());
        synchronized (userEmitters) {
            while (userEmitters.size() >= maxEmittersPerUser) {
                EmitterRegistration evicted = userEmitters.get(0);
                remove(evicted, "capacity-evicted", null, true, null);
            }
            userEmitters.add(registration);
        }

        CopyOnWriteArrayList<EmitterRegistration> taskEmitters = emittersByTask
                .computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>());
        taskEmitters.add(registration);
        emittersBySession
                .computeIfAbsent(context.sessionId(), ignored -> new CopyOnWriteArrayList<>())
                .add(registration);

        long delayMs = Math.max(0L, Duration.between(clock.instant(), context.expiresAt()).toMillis());
        if (!scheduler.isShutdown()) {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> expireIfDeadlineReached(registration),
                    delayMs,
                    TimeUnit.MILLISECONDS);
            registration.setExpiryFuture(future);
        }

        log.debug("Comment SSE opened projectId={} taskId={} userId={} sessionId={} emitterId={} active={}",
                projectId, taskId, userId, context.sessionId(), registration.id(), taskEmitters.size());

        emitter.onCompletion(() -> {
            log.debug("Comment SSE completed taskId={} emitterId={}", taskId, registration.id());
            remove(registration, "completion", null, false, null);
        });
        emitter.onTimeout(() -> {
            log.warn("Comment SSE timed out taskId={} emitterId={}", taskId, registration.id());
            remove(registration, "timeout", null, true, null);
        });
        emitter.onError(error -> {
            if (RealtimeSseSupport.isClientAbort(error)) {
                log.debug("Comment SSE client abort taskId={} emitterId={} reason={}",
                        taskId, registration.id(), error.getMessage());
            } else {
                log.warn("Comment SSE error taskId={} emitterId={} reason={}",
                        taskId, registration.id(), error.getMessage());
            }
            remove(registration, "error", error, false, null);
        });

        safeSendJson(registration, "comment.connected", Map.of("taskId", taskId, "projectId", projectId), "initial");
        return emitter;
    }

    public void publishCreated(TaskCommentDto comment) {
        if (comment != null) {
            publish(comment.taskId(), "comment.created", comment);
        }
    }

    public void publishUpdated(TaskCommentDto comment) {
        if (comment != null) {
            publish(comment.taskId(), "comment.updated", comment);
        }
    }

    public void publishDeleted(TaskCommentDto comment) {
        if (comment != null) {
            publish(comment.taskId(), "comment.deleted",
                    new TaskCommentDeletedEvent(comment.taskId(), comment.id(), comment.parentCommentId(), true));
        }
    }

    @EventListener
    public void onProjectAccessRevoked(ProjectAccessRevokedEvent event) {
        if (event == null) {
            return;
        }
        List<EmitterRegistration> userEmitters = emittersByUser.get(event.userId());
        if (userEmitters == null || userEmitters.isEmpty()) {
            return;
        }
        for (EmitterRegistration registration : List.copyOf(userEmitters)) {
            if (registration.projectId() == event.projectId()) {
                remove(registration, "project-access-revoked", null, true,
                        RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED);
            }
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
                    || !evaluateAccess(registration.projectId(), registration.context()).allowed()) {
                remove(registration, "security-changed", null, true, RealtimeAccessDenyReason.SESSION_REVOKED);
            }
        }
    }

    public void sendHeartbeats() {
        try {
            emittersByTask.forEach((taskId, emitters) -> {
                for (EmitterRegistration registration : emitters) {
                    safeSendHeartbeat(registration);
                }
            });
        } catch (Exception ex) {
            log.warn("Comment SSE heartbeat loop failed: {}", ex.getMessage());
        }
    }

    public void sweepExpired() {
        Instant now = clock.instant();
        emittersByTask.forEach((taskId, emitters) -> {
            for (EmitterRegistration registration : List.copyOf(emitters)) {
                if (!registration.context().expiresAt().isAfter(now)) {
                    remove(registration, "expired", null, true, RealtimeAccessDenyReason.AUTH_EXPIRED);
                } else {
                    RealtimeAccessDecision decision = evaluateAccess(registration.projectId(), registration.context());
                    if (!decision.allowed()) {
                        remove(registration, "authority-revoked", null, true, decision.denyReason());
                    }
                }
            }
        });
    }

    public int activeCountForTask(long taskId) {
        List<EmitterRegistration> list = emittersByTask.get(taskId);
        return list != null ? list.size() : 0;
    }

    public int activeCountForUser(long userId) {
        List<EmitterRegistration> list = emittersByUser.get(userId);
        return list != null ? list.size() : 0;
    }

    private void publish(Long taskId, String eventName, Object payload) {
        if (taskId == null) {
            return;
        }
        List<EmitterRegistration> emitters = emittersByTask.get(taskId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (EmitterRegistration registration : emitters) {
            safeSendJson(registration, eventName, payload, "publish");
        }
    }

    private void expireIfDeadlineReached(EmitterRegistration registration) {
        if (registration.removed().get()) {
            return;
        }
        if (!registration.context().expiresAt().isAfter(clock.instant())) {
            remove(registration, "expired-timer", null, true, RealtimeAccessDenyReason.AUTH_EXPIRED);
        } else {
            RealtimeAccessDecision decision = evaluateAccess(registration.projectId(), registration.context());
            if (!decision.allowed()) {
                remove(registration, "authority-revoked-timer", null, true, decision.denyReason());
            }
        }
    }

    private RealtimeAccessDecision evaluateAccess(long projectId, RealtimeAccessContext context) {
        if (realtimeAccessPort == null) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.SESSION_REVOKED, "Realtime access port unavailable");
        }
        if (context == null || context.expiresAt() == null) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.UNAUTHENTICATED, "Missing realtime session context");
        }
        if (!context.expiresAt().isAfter(clock.instant())) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.AUTH_EXPIRED, "Access token expired");
        }
        return realtimeAccessPort.evaluateProjectAccess(projectId, context);
    }

    private boolean safeSendJson(EmitterRegistration registration, String eventName, Object payload, String source) {
        if (registration.removed().get()) {
            return false;
        }
        RealtimeAccessDecision decision = evaluateAccess(registration.projectId(), registration.context());
        if (!decision.allowed()) {
            remove(registration, "unauthorized-" + source, null, true, decision.denyReason());
            return false;
        }
        try {
            registration.emitter().send(SseEmitter.event()
                    .name(eventName)
                    .data(payload, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Comment SSE send failed taskId={} emitterId={} source={} event={} reason={}",
                    registration.taskId(), registration.id(), source, eventName, ex.getMessage());
            remove(registration, "send-failed", ex, false, null);
            return false;
        }
    }

    private boolean safeSendHeartbeat(EmitterRegistration registration) {
        if (registration.removed().get()) {
            return false;
        }
        RealtimeAccessDecision decision = evaluateAccess(registration.projectId(), registration.context());
        if (!decision.allowed()) {
            remove(registration, "unauthorized-heartbeat", null, true, decision.denyReason());
            return false;
        }
        try {
            registration.emitter().send(SseEmitter.event().comment("keep-alive"));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Comment SSE heartbeat failed taskId={} emitterId={} reason={}",
                    registration.taskId(), registration.id(), ex.getMessage());
            remove(registration, "heartbeat-failed", ex, false, null);
            return false;
        }
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

        long taskId = registration.taskId();
        long userId = registration.context().userId();
        UUID sessionId = registration.context().sessionId();

        CopyOnWriteArrayList<EmitterRegistration> taskEmitters = emittersByTask.get(taskId);
        if (taskEmitters != null) {
            taskEmitters.remove(registration);
            if (taskEmitters.isEmpty()) {
                emittersByTask.remove(taskId, taskEmitters);
            }
        }

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
        log.debug("Comment SSE removed taskId={} userId={} emitterId={} reason={} detail={}",
                taskId, userId, registration.id(), reason, message);
    }

    private static final class EmitterRegistration {
        private final String id;
        private final long projectId;
        private final long taskId;
        private final RealtimeAccessContext context;
        private final SseEmitter emitter;
        private final AtomicBoolean removed = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> expiryFuture;

        private EmitterRegistration(
                String id,
                long projectId,
                long taskId,
                RealtimeAccessContext context,
                SseEmitter emitter
        ) {
            this.id = id;
            this.projectId = projectId;
            this.taskId = taskId;
            this.context = context;
            this.emitter = emitter;
        }

        private String id() {
            return id;
        }

        private long projectId() {
            return projectId;
        }

        private long taskId() {
            return taskId;
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
