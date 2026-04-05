package com.taskpilot.projects.projects.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity.MemberRole;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.projects.dto.CreateProjectRequest;
import com.taskpilot.projects.projects.dto.JoinProjectRequest;
import com.taskpilot.projects.projects.dto.MyProjectResponse;
import com.taskpilot.projects.projects.dto.ProjectMemberResponse;
import com.taskpilot.projects.projects.dto.ProjectResponse;
import com.taskpilot.projects.projects.dto.ProjectSummaryResponse;
import com.taskpilot.projects.projects.dto.UpdateProjectRequest;
import com.taskpilot.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl {

    private static final String PROJECT_CODE_PREFIX = "PRJ-";

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;

    // ==================== PROJECT CRUD ====================

    /**
     * Get all projects the user has joined
     */
    public Page<MyProjectResponse> getMyProjects(String email, String keyword, Pageable pageable) {
        Long userId = getCurrentUserIdByEmail(email);
        Pageable safePageable = buildSafePageable(pageable, "projectId", "userId", "joinedAt", "role");

        Page<ProjectMemberEntity> page;
        if (keyword != null && !keyword.isBlank()) {
            page = projectMemberRepository.findProjectsByUserIdAndKeyword(userId, keyword.trim(), safePageable);
        } else {
            page = projectMemberRepository.findProjectsByUserId(userId, safePageable);
        }

        return page.map(member -> new MyProjectResponse(
                        member.getProject().getId(),
                        member.getProject().getName(),
                        member.getProject().getDescription(),
                        member.getProject().getStatus(),
                        member.getRole(),
                        member.getProject().getStartDate(),
                        member.getProject().getEndDate(),
                        member.getJoinedAt() != null ? member.getJoinedAt() : member.getProject().getCreatedAt()));
    }

    /**
     * Get project detail by ID (user must be a member)
     */
    public ProjectResponse getProjectDetail(Long projectId, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        ProjectEntity project = findProjectById(projectId);
        validateUserIsMember(projectId, userId);
        return ProjectResponse.fromEntity(project);
    }

    /**
     * Create a new project (creator becomes PROJECT_MANAGER)
     */
    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        validateProjectDateRange(request.startDate(), request.endDate());

        if (projectRepository.existsByName(request.name())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Project name '" + request.name() + "' already exists");
        }

        ProjectEntity project = ProjectEntity.builder()
                .name(request.name())
                .description(request.description())
                .heuristicMode(request.heuristicMode() != null ? request.heuristicMode()
                        : ProjectEntity.HeuristicMode.BALANCED)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .build();

        projectRepository.save(project);

        // Add creator as PROJECT_MANAGER
        ProjectMemberEntity member = ProjectMemberEntity.builder()
                .projectId(project.getId())
                .project(project)
                .userId(userId)
                .role(MemberRole.MANAGER)
                .build();
        projectMemberRepository.save(member);

        return ProjectResponse.fromEntity(project);
    }

    /**
     * Update project (only PROJECT_MANAGER can update)
     */
    @Transactional
    public ProjectResponse updateProject(Long projectId, UpdateProjectRequest request, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        ProjectEntity project = findProjectById(projectId);
        validateUserIsProjectManager(projectId, userId);

        validateProjectDateRange(
                request.startDate() != null ? request.startDate() : project.getStartDate(),
                request.endDate() != null ? request.endDate() : project.getEndDate());

        if (request.name() != null && !request.name().isBlank()) {
            if (!request.name().equals(project.getName()) && projectRepository.existsByName(request.name())) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Project name '" + request.name() + "' already exists");
            }
            project.setName(request.name());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        if (request.status() != null) {
            project.setStatus(request.status());
        }
        if (request.heuristicMode() != null) {
            project.setHeuristicMode(request.heuristicMode());
        }
        if (request.startDate() != null) {
            project.setStartDate(request.startDate());
        }
        if (request.endDate() != null) {
            project.setEndDate(request.endDate());
        }

        projectRepository.save(project);
        return ProjectResponse.fromEntity(project);
    }

    /**
     * Join a project by invitation code (simple: project ID as code)
     */
    @Transactional
    public ProjectMemberResponse joinProject(JoinProjectRequest request, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        Long projectId = parseProjectCode(request.projectCode());

        ProjectEntity project = findProjectById(projectId);

        // Check if already a member
        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "You are already a member of this project");
        }

        // New member
        ProjectMemberEntity member = ProjectMemberEntity.builder()
                .projectId(project.getId())
                .project(project)
                .userId(userId)
                .role(MemberRole.MEMBER)
                .build();
        projectMemberRepository.save(member);

        return ProjectMemberResponse.fromEntity(member);
    }

    /**
     * Leave a project (remove membership record)
     */
    @Transactional
    public void leaveProject(Long projectId, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        findProjectById(projectId);

        ProjectMemberEntity member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "You are not a member of this project"));

        // Check if user is the only MANAGER
        if (member.getRole() == MemberRole.MANAGER) {
            long pmCount = projectMemberRepository.findMembers(projectId).stream()
                    .filter(m -> m.getRole() == MemberRole.MANAGER)
                    .count();
            if (pmCount == 1) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "You are the only Project Manager. Please assign another PM before leaving.");
            }
        }

        projectMemberRepository.delete(member);
    }

    /**
     * Get project summary (statistics)
     */
    public ProjectSummaryResponse getProjectSummary(Long projectId, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        ProjectEntity project = findProjectById(projectId);
        validateUserIsMember(projectId, userId);

        long totalMembers = projectMemberRepository.countMembers(projectId);

        // TODO: Integrate with Task module to get real task statistics
        // For now, return placeholder values
        return new ProjectSummaryResponse(
                projectId,
                project.getName(),
                totalMembers,
                0, // totalTasks
                0, // completedTasks
                0, // inProgressTasks
                0, // pendingTasks
                0.0 // completionRate
        );
    }

    /**
     * Get all active members of a project
     */
    public List<ProjectMemberResponse> getProjectMembers(Long projectId, String email) {
        Long userId = getCurrentUserIdByEmail(email);
        findProjectById(projectId);
        validateUserIsMember(projectId, userId);

        return projectMemberRepository.findMembers(projectId).stream()
                .map(ProjectMemberResponse::fromEntity)
                .toList();
    }

    // ==================== HELPER METHODS ====================

    private ProjectEntity findProjectById(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
    }

    private void validateUserIsMember(Long projectId, Long userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "You are not a member of this project");
        }
    }

    private void validateUserIsProjectManager(Long projectId, Long userId) {
        ProjectMemberEntity member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You are not a member of this project"));

        if (member.getRole() != MemberRole.MANAGER) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only Project Manager can perform this action");
        }
    }

    private Long getCurrentUserIdByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(user -> user.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED.value(), "User not found"));
    }

    private Long parseProjectCode(String projectCode) {
        String normalized = projectCode == null ? "" : projectCode.trim().toUpperCase();
        if (normalized.startsWith(PROJECT_CODE_PREFIX)) {
            normalized = normalized.substring(PROJECT_CODE_PREFIX.length());
        }

        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Invalid project code. Expected format: PRJ-<id> or <id>");
        }
    }

    private void validateProjectDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Project end date must be greater than or equal to start date");
        }
    }

    private Pageable buildSafePageable(Pageable pageable, String... allowedFields) {
        if (!pageable.getSort().isSorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                    Sort.by(Sort.Direction.DESC, "joinedAt"));
        }

        Set<String> allowed = Set.of(allowedFields);
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "joinedAt"));
            }
        }
        return pageable;
    }
}
