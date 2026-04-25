package com.taskpilot.ai.assignment.port.out;

import com.taskpilot.ai.entity.AiLogEntity;

public interface AiAuditPort {

    void save(AiLogEntity aiLog);
}
