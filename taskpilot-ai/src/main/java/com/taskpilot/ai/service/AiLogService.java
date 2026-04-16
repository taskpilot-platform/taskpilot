package com.taskpilot.ai.service;

import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.repository.AiLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Async
    @Transactional
    public void saveLog(Long userId, Long projectId, Long sessionId, Long chatMessageId,
            String request, String response, String reasoning,
            String actionTaken, Object toolOutput,
            String modelUsed, int tokensUsed, int durationMs) {
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
    public void updateFeedback(Long logId, String feedback) {
        aiLogRepository.findById(logId).ifPresent(aiLog -> {
            aiLog.setHumanFeedback(feedback);
            aiLogRepository.save(aiLog);
        });
    }
}
