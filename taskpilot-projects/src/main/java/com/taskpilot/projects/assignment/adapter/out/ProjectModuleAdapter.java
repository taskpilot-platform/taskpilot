package com.taskpilot.projects.assignment.adapter.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.taskpilot.contracts.assignment.dto.ProjectHeuristicConfigDto;
import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.assignment.port.out.ProjectPort;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ProjectModuleAdapter implements ProjectMemberPort, ProjectPort {
    private static final double DEFAULT_PERFORMANCE_SCORE = 0.5;

    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;

    @Override
    public List<ProjectMemberDto> findProjectMembers(Long projectId) {
        return projectMemberRepository.findMembers(projectId).stream()
                .map(member -> new ProjectMemberDto(member.getUserId(),
                        member.getRole() != null ? member
                                .getRole()
                                .name()
                                : null,
                        member.getPerformanceScore() != null
                                ? member.getPerformanceScore()
                                : DEFAULT_PERFORMANCE_SCORE))
                .toList();
    }

    @Override
    public List<Double> findRecentPerformanceScores(Long userId, int limit) {
        return projectMemberRepository.findRecentPerformanceScores(userId,
                PageRequest.of(0, limit));
    }

    @Override
    public Optional<ProjectHeuristicConfigDto> findById(Long projectId) {
        return projectRepository.findById(projectId)
                .map(project -> new ProjectHeuristicConfigDto(
                        project.getId(),
                        project.getHeuristicMode() != null
                                ? project.getHeuristicMode().name()
                                : null));
    }
}
