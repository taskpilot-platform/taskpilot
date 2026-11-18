package com.taskpilot.projects.assignment.adapter.out;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.taskpilot.contracts.assignment.dto.ProjectHeuristicConfigDto;
import com.taskpilot.contracts.assignment.dto.ProjectDueDto;
import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;
import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.assignment.port.out.ProjectPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;

@Component
public class ProjectModuleAdapter implements ProjectMemberPort, ProjectPort {
        private static final double DEFAULT_PERFORMANCE_SCORE = 0.5;

        private final ProjectMemberRepository projectMemberRepository;
        private final ProjectRepository projectRepository;
        private final UserPort userPort;

        @Autowired
        public ProjectModuleAdapter(
                        ProjectMemberRepository projectMemberRepository,
                        ProjectRepository projectRepository,
                        @Autowired(required = false) UserPort userPort) {
                this.projectMemberRepository = projectMemberRepository;
                this.projectRepository = projectRepository;
                this.userPort = userPort;
        }

        public ProjectModuleAdapter(
                        ProjectMemberRepository projectMemberRepository,
                        ProjectRepository projectRepository) {
                this(projectMemberRepository, projectRepository, null);
        }

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
        public boolean isProjectArchived(Long projectId) {
                if (projectId == null) {
                        return true;
                }
                return projectRepository.findById(projectId)
                                .map(project -> project.getStatus() == ProjectStatus.ARCHIVED)
                                .orElse(true);
        }
        @Override
        public String computeProjectRosterRevision(Long projectId) {
                if (projectId == null) {
                        return "untracked";
                }
                String projectStatus = projectRepository.findById(projectId)
                                .map(p -> p.getStatus() != null ? p.getStatus().name() : "UNKNOWN")
                                .orElse("MISSING");
                List<ProjectMemberEntity> members = projectMemberRepository.findMembers(projectId).stream()
                                .sorted(Comparator.comparing(ProjectMemberEntity::getUserId))
                                .toList();
                StringBuilder sb = new StringBuilder();
                sb.append("project=").append(projectId).append(':').append(projectStatus);
                for (ProjectMemberEntity member : members) {
                        String role = member.getRole() != null ? member.getRole().name() : "MEMBER";
                        UserProfileDto profile = userPort != null
                                        ? userPort.findById(member.getUserId()).orElse(null)
                                        : null;
                        String active = profile != null ? String.valueOf(profile.activeAccount()) : "unknown";
                        String status = profile != null && profile.status() != null ? profile.status() : "UNKNOWN";
                        sb.append('|').append(member.getUserId()).append(':')
                                        .append(role).append(':')
                                        .append(active).append(':')
                                        .append(status);
                }
                try {
                        byte[] digest = MessageDigest.getInstance("SHA-256")
                                        .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
                        return HexFormat.of().formatHex(digest).substring(0, 32);
                } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException("SHA-256 unavailable", e);
                }
        }

        @Override
        @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
        public void lockProjectForStorageWrite(Long projectId) {
                projectRepository.findByIdForUpdate(projectId).orElseThrow(() ->
                    new com.taskpilot.infrastructure.exception.BusinessException(404,"Project not found"));
        }
}
