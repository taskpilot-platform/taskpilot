package com.taskpilot.contracts.assignment.port.out;

import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics;
import java.util.Map;
import java.util.Set;

/** Current project-scoped features captured together at one database snapshot. */
public interface TaskPerformanceReadPort {
    Map<Long, TaskAssignmentMetrics> readProjectMetrics(long projectId, Set<Long> candidateUserIds);
}
