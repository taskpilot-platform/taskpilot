package com.taskpilot.ai.service;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Service
public class PendingAiActionService {

    private static final Duration ACTION_TTL = Duration.ofMinutes(10);
    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();

    public ConfirmationRequiredDto create(
            Long userId,
            Long sessionId,
            String toolName,
            String summary,
            Map<String, Object> arguments,
            Object preview,
            Supplier<Object> executor) {
        cleanupExpired();
        String actionId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        Instant expiresAt = Instant.now().plus(ACTION_TTL);
        actions.put(actionId, new PendingAction(userId, sessionId, toolName, summary, arguments, preview,
                createdAt, expiresAt, executor));

        log.info("[HumanInLoop] Pending AI action created: actionId={} tool={} user={} session={}",
                actionId, toolName, userId, sessionId);
        return new ConfirmationRequiredDto(true, actionId, toolName, summary, arguments, preview, expiresAt);
    }

    public Object confirm(String actionId, Long userId, Long sessionId) {
        cleanupExpired();
        PendingAction action = actions.remove(actionId);
        if (action == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "Pending action not found or expired");
        }
        if (!action.userId().equals(userId) || !action.sessionId().equals(sessionId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Pending action does not belong to this session");
        }

        log.info("[HumanInLoop] Confirming AI action: actionId={} tool={} user={} session={}",
                actionId, action.toolName(), userId, sessionId);
        try {
            return action.executor().get();
        } catch (Exception e) {
            log.error("[HumanInLoop] Action failed during confirmation: {}", e.getMessage());
            return "ERROR: Action failed to execute due to business rule validation: " + e.getMessage() + ". Please explain this to the user.";
        }
    }

    public Object confirmLatest(Long userId, Long sessionId) {
        cleanupExpired();
        return actions.entrySet().stream()
                .filter(entry -> entry.getValue().userId().equals(userId)
                        && entry.getValue().sessionId().equals(sessionId))
                .max(Map.Entry.comparingByValue((left, right) -> left.createdAt().compareTo(right.createdAt())))
                .map(entry -> confirm(entry.getKey(), userId, sessionId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Pending action not found or expired"));
    }

    public void cancel(String actionId, Long userId, Long sessionId) {
        PendingAction action = actions.get(actionId);
        if (action == null) {
            return;
        }
        if (!action.userId().equals(userId) || !action.sessionId().equals(sessionId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Pending action does not belong to this session");
        }
        actions.remove(actionId);
        log.info("[HumanInLoop] Cancelled AI action: actionId={} tool={}", actionId, action.toolName());
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        actions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record PendingAction(
            Long userId,
            Long sessionId,
            String toolName,
            String summary,
            Map<String, Object> arguments,
            Object preview,
            Instant createdAt,
            Instant expiresAt,
            Supplier<Object> executor) {
    }
}
