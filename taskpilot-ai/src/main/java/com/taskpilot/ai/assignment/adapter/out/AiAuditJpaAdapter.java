package com.taskpilot.ai.assignment.adapter.out;

import org.springframework.stereotype.Component;

import com.taskpilot.ai.assignment.port.out.AiAuditPort;
import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.repository.AiLogRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AiAuditJpaAdapter implements AiAuditPort {

    private final AiLogRepository aiLogRepository;

    @Override
    public void save(AiLogEntity aiLog) {
        aiLogRepository.save(aiLog);
    }
}
