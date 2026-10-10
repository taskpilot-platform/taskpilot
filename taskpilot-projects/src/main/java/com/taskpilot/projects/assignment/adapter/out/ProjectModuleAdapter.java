package com.taskpilot.projects.assignment.adapter.out;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.taskpilot.contracts.assignment.dto.ProjectHeuristicConfigDto;
import com.taskpilot.contracts.assignment.dto.ProjectDueDto;
import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.assignment.port.out.ProjectPort;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.TaskRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ProjectModuleAdapter implements ProjectMemberPort, ProjectPort {
        private static final double DEFAULT_PERFORMANCE_SCORE = 0.5;
        private static final Set<TaskStatus> TERMINAL_TASK_STATUSES = Set.of(TaskStatus.DONE);

        private final ProjectMemberRepository projectMemberRepository;
        private final ProjectRepository projectRepository;
        private final TaskRepository taskRepository;

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
        public List<ProjectDueDto> findUpcomingProjects(Long userId, LocalDate fromDate, LocalDate toDate, int limit) {
                return projectMemberRepository.findUpcomingProjects(userId, fromDate, toDate,
                                PageRequest.of(0, limit))
                                .stream()
                                .map(project -> new ProjectDueDto(
                                                project.getId(),
                                                project.getName(),
                                                project.getEndDate(),
                                                project.getStatus() != null ? project.getStatus().name() : null))
                                .toList();
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

        @Override
        public boolean isProjectMember(Long projectId, Long userId) {
                return projectMemberRepository.existsByProjectIdAndUserId(projectId, userId);
        }

        @Override
        public boolean isProjectManager(Long projectId, Long userId) {
                if (projectId == null || userId == null) {
                        return false;
                }
                return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                                .map(member -> member.getRole() == MemberRole.MANAGER)
                                .orElse(false);
        }

        @Override
        public Map<Long, Integer> countActiveAssignedTasksByProject(Long projectId) {
                if (projectId == null) {
                        return Map.of();
                }
                List<Object[]> rows = taskRepository.countActiveAssignedTasksByProject(projectId, TERMINAL_TASK_STATUSES);
                Map<Long, Integer> counts = new HashMap<>();
                for (Object[] row : rows) {
                        if (row != null && row.length >= 2 && row[0] instanceof Long assigneeId && row[1] instanceof Number count) {
                                counts.put(assigneeId, count.intValue());
                        }
                }
                return counts;
        }
}
