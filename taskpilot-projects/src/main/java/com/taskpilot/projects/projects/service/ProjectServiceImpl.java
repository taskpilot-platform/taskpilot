package com.taskpilot.projects.projects.service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.project.dto.AccessRevocationReason;
import com.taskpilot.contracts.project.dto.ProjectAccessRevokedEvent;
import com.taskpilot.contracts.project.exception.SoleActiveManagerException;
import com.taskpilot.contracts.storage.ProjectStorageInventoryContributor;
import com.taskpilot.contracts.storage.StorageCleanupPort;
import com.taskpilot.contracts.storage.StorageCleanupRequest;
import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.event.ProjectMemberEvent;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.util.PageableUtils;
import com.taskpilot.infrastructure.util.ValidationUtils;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectFileEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.enums.HeuristicMode;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.LabelRepository;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectInvitationRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.service.ProjectSecurityService;
import com.taskpilot.projects.projects.dto.CreateProjectRequest;
import com.taskpilot.projects.projects.dto.JoinProjectRequest;
import com.taskpilot.projects.projects.dto.MyProjectResponse;
import com.taskpilot.projects.projects.dto.ProjectMemberResponse;
import com.taskpilot.projects.projects.dto.ProjectResponse;
import com.taskpilot.projects.projects.dto.ProjectSummaryResponse;
import com.taskpilot.projects.projects.dto.UpdateProjectRequest;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProjectServiceImpl {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectInvitationRepository projectInvitationRepository;
    private final ProjectSecurityService projectSecurityService;
    private final UserPort userPort;
    private final AccountAuthStatePort accountAuthStatePort;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskRepository taskRepository;
    private final SprintRepository sprintRepository;
    private final LabelRepository labelRepository;
    private final UserProfilePort userProfilePort;
    private final ProjectFileRepository projectFileRepository;
    private final StorageCleanupPort storageCleanupPort;
    private final List<ProjectStorageInventoryContributor> storageInventoryContributors;

    // ==================== PROJECT CRUD ====================

    public Page<MyProjectResponse> getMyProjects(Long userId, String keyword, Pageable pageable) {
        Pageable safePageable = PageableUtils.sanitize(pageable, "joinedAt", Sort.Direction.DESC, "projectId", "userId", "joinedAt", "role");

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

    public ProjectResponse getProjectDetail(Long projectId, Long userId) {
        ProjectEntity project = projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);
        return ProjectResponse.fromEntity(project);
    }

    /**
     * Create a new project (creator becomes PROJECT_MANAGER).
     * Persists and locks the new project row before locking the creator account row
     * so concurrent self-deactivation observes consistent ordering.
     */
    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request, Long userId) {
        ValidationUtils.validateDateRange(request.startDate(), request.endDate(),
                "Project end date must be greater than or equal to start date");

        if (projectRepository.existsByName(request.name())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Project name '" + request.name() + "' already exists");
        }

        ProjectEntity project = ProjectEntity.builder()
                .name(request.name())
                .description(request.description())
                .heuristicMode(request.heuristicMode() != null ? request.heuristicMode()
                        : HeuristicMode.BALANCED)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .build();

        project = projectRepository.saveAndFlush(project);

        AccountAuthStateDto creatorState = accountAuthStatePort.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(), "Account not found"));
        if (!ProjectAccountLifecycleAdapter.isEffectiveActive(creatorState)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Inactive accounts cannot create projects");
        }

        ProjectMemberEntity member = ProjectMemberEntity.builder()
                .projectId(project.getId())
                .project(project)
                .userId(userId)
                .role(MemberRole.MANAGER)
                .build();
        projectMemberRepository.saveAndFlush(member);

        return ProjectResponse.fromEntity(project);
    }

    @Transactional
    public ProjectResponse updateProject(Long projectId, UpdateProjectRequest request, Long userId) {
        ProjectEntity project = projectSecurityService.requireProject(projectId);
        projectSecurityService.validateManager(projectId, userId);

        projectSecurityService.validateProjectNotArchived(project);

        ValidationUtils.validateDateRange(
                request.startDate() != null ? request.startDate() : project.getStartDate(),
                request.endDate() != null ? request.endDate() : project.getEndDate(),
                "Project end date must be greater than or equal to start date");

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
        if (request.workflowMode() != null) {
            project.setWorkflowMode(request.workflowMode());
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
     * Guessable PRJ-ID enrollment is intentionally disabled in P2.4.
     */
    @Transactional
    public ProjectMemberResponse joinProject(JoinProjectRequest request, Long userId) {
        throw new BusinessException(HttpStatus.GONE.value(),
                "Joining projects by numeric PRJ-ID code is disabled. Request a private invitation link from a project manager.");
    }

    /**
     * Leave a project under canonical project -> users -> tasks lock ordering,
     * preserving at least one effective ACTIVE manager and revoking prior invitations.
     */
    @Transactional
    public void leaveProject(Long projectId, Long userId) {
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));

        List<ProjectMemberEntity> members = projectMemberRepository.findMembers(projectId);
        Set<Long> affectedUserIds = members.stream()
                .map(ProjectMemberEntity::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        affectedUserIds.add(userId);
        Map<Long, AccountAuthStateDto> accountStates = accountAuthStatePort.lockAndLoadByIds(affectedUserIds);

        ProjectMemberEntity member = members.stream()
                .filter(m -> m.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "You are not a member of this project"));

        if (member.getRole() == MemberRole.MANAGER) {
            boolean hasOtherActiveManager = members.stream()
                    .filter(m -> m.getRole() == MemberRole.MANAGER && !m.getUserId().equals(userId))
                    .anyMatch(m -> ProjectAccountLifecycleAdapter.isEffectiveActive(accountStates.get(m.getUserId())));
            if (!hasOtherActiveManager) {
                throw new SoleActiveManagerException(
                        List.of(projectId),
                        "You are the sole active Project Manager. Assign another active PM before leaving.");
            }
        }

        Instant now = Instant.now();
        AccountAuthStateDto leavingAccount = accountStates.get(userId);
        if (leavingAccount != null && leavingAccount.email() != null) {
            projectInvitationRepository.revokePendingByProjectIdAndEmail(
                    projectId,
                    leavingAccount.email().trim().toLowerCase(Locale.ROOT),
                    now,
                    userId,
                    "MEMBER_LEFT");
        }

        clearOrphanTasksOnRemoval(project, userId, members, accountStates, "left the project");

        projectMemberRepository.delete(member);
        projectMemberRepository.flush();

        long rosterVersion = project.bumpRosterVersion();
        projectRepository.saveAndFlush(project);

        publishAfterCommit(new ProjectAccessRevokedEvent(
                UUID.randomUUID(), projectId, userId, rosterVersion, now, AccessRevocationReason.MEMBER_LEFT));

        String leavingMemberName = userPort.findById(userId)
                .map(user -> user.fullName())
                .orElse("A member");
        for (ProjectMemberEntity mgr : members) {
            if (mgr.getRole() == MemberRole.MANAGER
                    && !mgr.getUserId().equals(userId)
                    && ProjectAccountLifecycleAdapter.isEffectiveActive(accountStates.get(mgr.getUserId()))) {
                eventPublisher.publishEvent(new ProjectMemberEvent(
                        mgr.getUserId(),
                        "Thành viên rời dự án",
                        leavingMemberName + " đã rời khỏi dự án \"" + project.getName() + "\".",
                        "/projects", project.getId()));
            }
        }
    }

    /**
     * Update member role under canonical project -> users lock ordering.
     * Prevents demoting the sole effective ACTIVE manager and promoting inactive accounts.
     */
    @Transactional
    public void updateMemberRole(Long projectId, Long targetUserId, MemberRole newRole, Long currentUserId) {
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        projectSecurityService.validateProjectNotArchived(project);

        List<ProjectMemberEntity> members = projectMemberRepository.findMembers(projectId);
        Set<Long> affectedUserIds = members.stream()
                .map(ProjectMemberEntity::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        affectedUserIds.add(currentUserId);
        affectedUserIds.add(targetUserId);
        Map<Long, AccountAuthStateDto> accountStates = accountAuthStatePort.lockAndLoadByIds(affectedUserIds);

        ProjectMemberEntity caller = members.stream()
                .filter(m -> m.getUserId().equals(currentUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You are not a member of this project"));
        if (caller.getRole() != MemberRole.MANAGER
                || !ProjectAccountLifecycleAdapter.isEffectiveActive(accountStates.get(currentUserId))) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only an active Project Manager can update member roles");
        }

        ProjectMemberEntity targetMember = members.stream()
                .filter(m -> m.getUserId().equals(targetUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Member not found in project"));

        if (targetMember.getRole() == newRole) {
            return;
        }

        if (newRole == MemberRole.MANAGER) {
            AccountAuthStateDto targetState = accountStates.get(targetUserId);
            if (!ProjectAccountLifecycleAdapter.isEffectiveActive(targetState)) {
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Cannot promote an inactive account to Project Manager");
            }
        }

        if (targetMember.getRole() == MemberRole.MANAGER && newRole != MemberRole.MANAGER) {
            boolean hasOtherActiveManager = members.stream()
                    .filter(m -> m.getRole() == MemberRole.MANAGER && !m.getUserId().equals(targetUserId))
                    .anyMatch(m -> ProjectAccountLifecycleAdapter.isEffectiveActive(accountStates.get(m.getUserId())));
            if (!hasOtherActiveManager) {
                throw new SoleActiveManagerException(
                        List.of(projectId),
                        "Project must retain at least one active manager");
            }
        }

        boolean demotedFromManager = targetMember.getRole() == MemberRole.MANAGER && newRole != MemberRole.MANAGER;
        targetMember.setRole(newRole);
        projectMemberRepository.saveAndFlush(targetMember);

        long rosterVersion = project.bumpRosterVersion();
        if (newRole == MemberRole.MANAGER && Boolean.TRUE.equals(project.getNeedsManagerAttention())) {
            project.setNeedsManagerAttention(Boolean.FALSE);
            project.setManagementAttentionReason(null);
            project.setManagementAttentionSince(null);
            project.setManagementAttentionEventId(null);
        }
        projectRepository.saveAndFlush(project);

        if (demotedFromManager) {
            publishAfterCommit(new ProjectAccessRevokedEvent(
                    UUID.randomUUID(), projectId, targetUserId, rosterVersion, Instant.now(),
                    AccessRevocationReason.ROLE_DEMOTED));
        }
    }

    /**
     * Remove member under canonical project -> users -> tasks lock ordering,
     * preserving at least one effective ACTIVE manager and revoking prior invitations.
     */
    @Transactional
    public void removeMember(Long projectId, Long targetUserId, Long currentUserId) {
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        projectSecurityService.validateProjectNotArchived(project);

        List<ProjectMemberEntity> members = projectMemberRepository.findMembers(projectId);
        Set<Long> affectedUserIds = members.stream()
                .map(ProjectMemberEntity::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        affectedUserIds.add(currentUserId);
        affectedUserIds.add(targetUserId);
        Map<Long, AccountAuthStateDto> accountStates = accountAuthStatePort.lockAndLoadByIds(affectedUserIds);

        ProjectMemberEntity caller = members.stream()
                .filter(m -> m.getUserId().equals(currentUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You are not a member of this project"));
        if (caller.getRole() != MemberRole.MANAGER
                || !ProjectAccountLifecycleAdapter.isEffectiveActive(accountStates.get(currentUserId))) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only an active Project Manager can remove members");
        }

        ProjectMemberEntity targetMember = members.stream()
                .filter(m -> m.getUserId().equals(targetUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Member not found in project"));

        if (targetMember.getRole() == MemberRole.MANAGER) {
            boolean hasOtherActiveManager = members.stream()
                    .filter(m -> m.getRole() == MemberRole.MANAGER && !m.getUserId().equals(targetUserId))
                    .anyMatch(m -> ProjectAccountLifecycleAdapter.isEffectiveActive(accountStates.get(m.getUserId())));
            if (!hasOtherActiveManager) {
                throw new SoleActiveManagerException(
                        List.of(projectId),
                        "Project must retain at least one active manager");
            }
        }

        Instant now = Instant.now();
        AccountAuthStateDto targetAccount = accountStates.get(targetUserId);
        if (targetAccount != null && targetAccount.email() != null) {
            projectInvitationRepository.revokePendingByProjectIdAndEmail(
                    projectId,
                    targetAccount.email().trim().toLowerCase(Locale.ROOT),
                    now,
                    currentUserId,
                    "MEMBER_REMOVED");
        }

        clearOrphanTasksOnRemoval(project, targetUserId, members, accountStates, "was removed from the project");

        projectMemberRepository.delete(targetMember);
        projectMemberRepository.flush();

        long rosterVersion = project.bumpRosterVersion();
        projectRepository.saveAndFlush(project);

        publishAfterCommit(new ProjectAccessRevokedEvent(
                UUID.randomUUID(), projectId, targetUserId, rosterVersion, now,
                AccessRevocationReason.MEMBER_REMOVED));
    }

    // ==================== PROJECT LIFECYCLE ====================

    @Transactional
    public void archiveProject(Long projectId, Long userId) {
        ProjectEntity project = projectSecurityService.requireProject(projectId);
        projectSecurityService.validateManager(projectId, userId);

        project.setStatus(ProjectStatus.ARCHIVED);
        projectRepository.save(project);
    }

    @Transactional
    public void restoreProject(Long projectId, Long userId) {
        ProjectEntity project = projectSecurityService.requireProject(projectId);
        projectSecurityService.validateManager(projectId, userId);

        project.setStatus(ProjectStatus.ACTIVE);
        projectRepository.save(project);
    }

    @Transactional
    public void deleteProject(Long projectId, Long userId) {
        projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(404,"Project not found"));
        projectSecurityService.validateManager(projectId, userId);

        List<StorageCleanupRequest> cleanupRequests = inventoryProjectStorageBeforeDelete(projectId);

        taskRepository.deleteByProjectId(projectId);
        sprintRepository.deleteByProjectId(projectId);
        labelRepository.deleteByProjectId(projectId);
        projectMemberRepository.deleteByProjectId(projectId);
        projectRepository.deleteProjectById(projectId);

        if (storageCleanupPort != null && !cleanupRequests.isEmpty()) {
            storageCleanupPort.enqueueCleanupBatch(cleanupRequests);
        }
    }

    private List<StorageCleanupRequest> inventoryProjectStorageBeforeDelete(Long projectId) {
        List<StorageCleanupRequest> requests = new ArrayList<>();
        if (projectFileRepository != null) {
            for (ProjectFileEntity file : projectFileRepository.findByProjectId(projectId)) {
                if (file.getStorageKey() != null && !file.getStorageKey().isBlank()) {
                    requests.add(new StorageCleanupRequest(
                            StorageObjectLocator.of(file.getStorageBucket(), file.getStorageKey()),
                            "PROJECT_FILE",
                            file.getId() != null ? String.valueOf(file.getId()) : null,
                            projectId,
                            null
                    ));
                }
            }
        }
        if (storageInventoryContributors != null) {
            for (ProjectStorageInventoryContributor contributor : storageInventoryContributors) {
                List<StorageObjectLocator> locators = contributor.listProjectOwnedObjects(projectId);
                if (locators == null) {
                    continue;
                }
                for (StorageObjectLocator locator : locators) {
                    if (locator != null) {
                        requests.add(new StorageCleanupRequest(
                                locator,
                                contributor.sourceType(),
                                String.valueOf(projectId),
                                projectId,
                                null
                        ));
                    }
                }
            }
        }
        return requests;
    }

    public ProjectSummaryResponse getProjectSummary(Long projectId, Long userId) {
        ProjectEntity project = projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        long totalMembers = projectMemberRepository.countMembers(projectId);

        long totalTasks = taskRepository.countByProjectId(projectId);
        long todoTasks = taskRepository.countByProjectIdAndStatus(projectId, TaskStatus.TODO);
        long inProgressTasks = taskRepository.countByProjectIdAndStatus(projectId, TaskStatus.IN_PROGRESS);
        long reviewTasks = taskRepository.countByProjectIdAndStatus(projectId, TaskStatus.REVIEW);
        long doneTasks = taskRepository.countByProjectIdAndStatus(projectId, TaskStatus.DONE);

        double completionRate = totalTasks > 0 ? ((double) doneTasks / totalTasks) * 100.0 : 0.0;

        return new ProjectSummaryResponse(
                projectId,
                project.getName(),
                totalMembers,
                totalTasks,
                todoTasks,
                inProgressTasks,
                reviewTasks,
                doneTasks,
                Math.round(completionRate * 10.0) / 10.0);
    }

    public List<ProjectMemberResponse> getProjectMembers(Long projectId, Long userId) {
        projectSecurityService.requireProject(projectId);
        projectSecurityService.validateMember(projectId, userId);

        List<ProjectMemberEntity> members = projectMemberRepository.findMembers(projectId);

        Set<Long> userIds = members.stream()
                .map(ProjectMemberEntity::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserProfileLiteDto> profileMap = userProfilePort.findLiteByIds(userIds).stream()
                .collect(Collectors.toMap(UserProfileLiteDto::id, p -> p));

        return members.stream()
                .map(member -> {
                    var liteProfile = profileMap.get(member.getUserId());
                    var fullProfile = userPort.findById(member.getUserId()).orElse(null);
                    return ProjectMemberResponse.fromEntityWithProfile(
                            member,
                            liteProfile != null ? liteProfile.fullName() : null,
                            fullProfile != null ? fullProfile.email() : null,
                            liteProfile != null ? liteProfile.avatarUrl() : null);
                })
                .toList();
    }

    private void clearOrphanTasksOnRemoval(
            ProjectEntity project,
            long removedUserId,
            List<ProjectMemberEntity> members,
            Map<Long, AccountAuthStateDto> accountStates,
            String reasonPhrase) {
        List<TaskEntity> activeAssigned = taskRepository.findActiveAssignedByProjectIdAndAssigneeIdForUpdate(
                project.getId(), removedUserId);
        if (activeAssigned.isEmpty()) {
            return;
        }
        for (TaskEntity task : activeAssigned) {
            task.setAssigneeId(null);
            if (task.getStatus() == TaskStatus.REVIEW) {
                task.setStatus(TaskStatus.IN_PROGRESS);
            }
        }
        taskRepository.saveAll(activeAssigned);

        for (ProjectMemberEntity mgr : members) {
            if (mgr.getRole() == MemberRole.MANAGER
                    && !mgr.getUserId().equals(removedUserId)
                    && ProjectAccountLifecycleAdapter.isEffectiveActive(accountStates.get(mgr.getUserId()))) {
                eventPublisher.publishEvent(new ProjectMemberEvent(
                        mgr.getUserId(),
                        "Orphaned tasks require reassignment",
                        activeAssigned.size() + " active task(s) in project \"" + project.getName()
                                + "\" were unassigned because the assignee " + reasonPhrase + ".",
                        "/projects/" + project.getId() + "/board", project.getId()));
            }
        }
    }

    private void publishAfterCommit(Object event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(event);
                }
            });
        } else {
            eventPublisher.publishEvent(event);
        }
    }
}
