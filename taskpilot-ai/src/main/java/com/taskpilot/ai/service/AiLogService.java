package com.taskpilot.ai.service;

import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.repository.AiLogRepository;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.observability.TaskPilotMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiLogService {
    private final AiLogRepository aiLogRepository;
    private TaskPilotMetrics metrics = TaskPilotMetrics.noop();

    @Autowired(required = false)
    public void setMetrics(TaskPilotMetrics metrics) {
        this.metrics = metrics != null ? metrics : TaskPilotMetrics.noop();
    }

    @Async
    @Transactional
    public void saveLog(Long userId, Long projectId, Long sessionId, Long chatMessageId,
            String request, String response, String reasoning,
            String actionTaken, Object toolOutput,
            String modelUsed, int tokensUsed, int durationMs) {
        metrics.recordAiCompletion(modelUsed, tokensUsed, durationMs);
        try {
            AiLogEntity log = AiLogEntity.builder()
                    .userId(userId)
                    .projectId(projectId)
                    .sessionId(sessionId)
                    .chatMessageId(chatMessageId)
                    .request(request)
                    .response(response)
                    .reasoning(reasoning)
                    .actionTaken(actionTaken)
                    .toolOutput(toolOutput)
                    .humanFeedback("PENDING")
                    .modelUsed(modelUsed)
                    .tokensUsed(tokensUsed)
                    .durationMs(durationMs)
                    .build();
            aiLogRepository.save(log);
        } catch (Exception e) {
            log.error("[AiLogService] Failed to save AI log for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<AiLogEntity> getLogsForAdmin(Long userId, Long projectId,
            Instant from, Instant to, Pageable pageable) {
        return aiLogRepository.findByFilters(userId, projectId, from, to, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AiLogEntity> getLogsForUser(Long userId, Pageable pageable) {
        return aiLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional
    public void updateFeedback(Long logId, String feedback, CurrentActor actor) {
        AiLogEntity aiLog = aiLogRepository.findById(logId)
                .orElseThrow(() -> new BusinessException(404, "AI log not found"));
        // Feedback is the requesting user's decision; global admin read access does not imply ownership.
        if (actor == null || !java.util.Objects.equals(aiLog.getUserId(), actor.userId())) {
            throw new BusinessException(403, "Only the owner can update AI feedback");
        }
        if (feedback == null || !java.util.Set.of("ACCEPTED", "REJECTED", "PENDING").contains(feedback)) {
            throw new BusinessException(400, "Invalid feedback");
        }
        aiLog.setHumanFeedback(feedback);
        aiLogRepository.save(aiLog);
    }
}
