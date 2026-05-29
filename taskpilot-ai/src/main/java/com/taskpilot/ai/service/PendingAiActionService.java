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
        Instant expiresAt = Instant.now().plus(ACTION_TTL);
        actions.put(actionId, new PendingAction(userId, sessionId, toolName, summary, arguments, preview,
                expiresAt, executor));

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
        return action.executor().get();
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
            Instant expiresAt,
            Supplier<Object> executor) {
    }
}
