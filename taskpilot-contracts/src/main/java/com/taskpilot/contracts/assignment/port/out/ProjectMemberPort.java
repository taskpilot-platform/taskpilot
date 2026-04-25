package com.taskpilot.contracts.assignment.port.out;

import java.util.List;

import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;

public interface ProjectMemberPort {

    List<ProjectMemberDto> findProjectMembers(Long projectId);

    List<Double> findRecentPerformanceScores(Long userId, int limit);
}
