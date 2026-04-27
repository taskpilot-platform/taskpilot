package com.taskpilot.contracts.assignment.port.out;

import java.util.List;
import java.time.LocalDate;

import com.taskpilot.contracts.assignment.dto.ProjectDueDto;
import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;

public interface ProjectMemberPort {

    List<ProjectMemberDto> findProjectMembers(Long projectId);

    List<Double> findRecentPerformanceScores(Long userId, int limit);

    List<ProjectDueDto> findUpcomingProjects(Long userId, LocalDate fromDate, LocalDate toDate, int limit);
}
