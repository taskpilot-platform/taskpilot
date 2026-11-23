package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.SprintSummaryDto;

import java.util.List;
import java.util.UUID;

public interface SprintQueryPort {
    List<SprintSummaryDto> getSprintsByProject(Long projectId, Long requesterUserId);

    // Read Tools
    Object getSprintBacklog(Long projectId, Long requesterUserId);
    Object getSprintBoard(Long projectId, Long requesterUserId);

    // Write Tools
    SprintSummaryDto createSprint(Long projectId, String name, String startDate, String endDate, String goal, Long requesterUserId);
    SprintSummaryDto updateSprint(Long projectId, Long sprintId, String name, String startDate, String endDate, String goal,
            Long requesterUserId);
    void deleteSprint(Long projectId, Long sprintId, Long requesterUserId);
    SprintSummaryDto startSprint(Long projectId, Long sprintId, Long requesterUserId);
    SprintSummaryDto completeSprint(Long projectId, Long sprintId, Long expectedSprintVersion,
            String carryoverDestination, Long targetSprintId, UUID requestId, Long requesterUserId);
    Object assignTaskToSprint(Long taskId, Long sprintId, Long expectedTaskVersion, UUID requestId, Long requesterUserId);
}
