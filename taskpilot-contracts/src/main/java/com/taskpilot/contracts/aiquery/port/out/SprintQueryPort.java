package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.SprintSummaryDto;

import java.util.List;

public interface SprintQueryPort {
    List<SprintSummaryDto> getSprintsByProject(Long projectId, Long requesterUserId);
}
