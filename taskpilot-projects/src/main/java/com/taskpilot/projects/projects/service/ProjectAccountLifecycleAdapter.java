package com.taskpilot.projects.projects.service;

import com.taskpilot.contracts.project.dto.AccessRevocationReason;
import com.taskpilot.contracts.project.dto.ProjectAccessRevokedEvent;
import com.taskpilot.contracts.project.dto.ProjectManagementAttentionRequired;
import com.taskpilot.contracts.project.exception.RetryableRosterChangeException;
import com.taskpilot.contracts.project.exception.SoleActiveManagerException;
import com.taskpilot.contracts.project.port.out.ProjectAccountLifecycleGuard;
import com.taskpilot.contracts.project.port.out.ProjectAccountLifecycleRecoveryPort;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.UserLifecycleRecoveryRequestedEvent;
import com.taskpilot.contracts.user.event.ProjectMemberEvent;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectManagementAttentionAuditEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.entity.UserProjectReconciliationStateEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.enums.TaskStatus;
import com.taskpilot.projects.common.repository.ProjectManagementAttentionAuditRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.repository.UserProjectReconciliationStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;

/**
 * Projects-owned lifecycle guard and asynchronous admin-suspension recovery consumer.
 * Enforces canonical lock ordering: projects (ascending) -> users (ascending) -> tasks (ascending).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAccountLifecycleAdapter implements ProjectAccountLifecycleGuard, ProjectAccountLifecycleRecoveryPort {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final TaskRepository taskRepository;
    private final ProjectManagementAttentionAuditRepository attentionAuditRepository;
    private final UserProjectReconciliationStateRepository reconciliationStateRepository;
    private final AccountAuthStatePort accountAuthStatePort;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public List<Long> findCandidateManagerialProjectIds(long userId) {
        return projectMemberRepository.findManagerialProjectIdsByUserId(userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<Long> lockProjectsAndFindAffectedUserIds(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Set.of();
        }
        List<Long> sortedProjectIds = projectIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .sorted()
                .toList();
        for (Long projectId : sortedProjectIds) {
            projectRepository.findByIdForUpdate(projectId);
        }
        return new LinkedHashSet<>(projectMemberRepository.findDistinctUserIdsByProjectIdIn(sortedProjectIds));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void assertCanSelfDeactivate(long userId) {
        List<Long> candidateProjects = projectMemberRepository.findManagerialProjectIdsByUserId(userId);
        Set<Long> affectedUsers = new LinkedHashSet<>(lockProjectsAndFindAffectedUserIds(candidateProjects));
        affectedUsers.add(userId);
        accountAuthStatePort.lockAndLoadByIds(affectedUsers);
        assertCanSelfDeactivate(userId, Set.copyOf(candidateProjects));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertCanSelfDeactivate(long userId, Set<Long> lockedProjectIds) {
        List<Long> currentManagerialProjects = projectMemberRepository.findManagerialProjectIdsByUserId(userId);
        Set<Long> lockedSet = lockedProjectIds != null ? lockedProjectIds : Set.of();

        List<Long> missingProjects = currentManagerialProjects.stream()
                .filter(pid -> !lockedSet.contains(pid))
                .sorted()
                .toList();
        if (!missingProjects.isEmpty()) {
            throw new RetryableRosterChangeException(missingProjects);
        }
        if (currentManagerialProjects.isEmpty()) {
            return;
        }

        List<ProjectMemberEntity> allMembers = projectMemberRepository.findByProjectIdIn(currentManagerialProjects);
        Map<Long, List<ProjectMemberEntity>> membersByProject = new LinkedHashMap<>();
        Set<Long> managerUserIds = new LinkedHashSet<>();
        for (ProjectMemberEntity member : allMembers) {
            membersByProject.computeIfAbsent(member.getProjectId(), k -> new ArrayList<>()).add(member);
            if (member.getRole() == MemberRole.MANAGER) {
                managerUserIds.add(member.getUserId());
            }
        }

        Map<Long, AccountAuthStateDto> accountStates = accountAuthStatePort.findByIds(managerUserIds);
        List<Long> blockingProjectIds = new ArrayList<>();

        for (Long projectId : currentManagerialProjects) {
            ProjectEntity project = projectRepository.findById(projectId).orElse(null);
            if (project == null || project.getStatus() == ProjectStatus.ARCHIVED) {
                continue;
            }
            List<ProjectMemberEntity> projectMembers = membersByProject.getOrDefault(projectId, List.of());
            boolean hasOtherActiveManager = projectMembers.stream()
                    .filter(m -> m.getRole() == MemberRole.MANAGER && !m.getUserId().equals(userId))
                    .anyMatch(m -> isEffectiveActive(accountStates.get(m.getUserId())));
            if (!hasOtherActiveManager) {
                blockingProjectIds.add(projectId);
            }
        }

        if (!blockingProjectIds.isEmpty()) {
            throw new SoleActiveManagerException(
                    blockingProjectIds,
                    "Cannot self-deactivate while you are the sole active manager of project(s) "
                            + blockingProjectIds + ". Transfer management to another active member first.");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileUserLifecycle(UserLifecycleRecoveryRequestedEvent event) {
        if (event == null) {
            return;
        }
        long userId = event.userId();
        List<Long> memberProjectIds = projectMemberRepository.findProjectIdsByUserId(userId);
        List<Long> sortedProjectIds = memberProjectIds.stream().distinct().sorted().toList();

        // Canonical lock order: projects (ascending) -> users (ascending) -> tasks (ascending).
        Map<Long, ProjectEntity> lockedProjects = new LinkedHashMap<>();
        for (Long projectId : sortedProjectIds) {
            projectRepository.findByIdForUpdate(projectId).ifPresent(p -> lockedProjects.put(p.getId(), p));
        }

        Set<Long> affectedUserIds = new LinkedHashSet<>();
        affectedUserIds.add(userId);
        if (!sortedProjectIds.isEmpty()) {
            affectedUserIds.addAll(projectMemberRepository.findDistinctUserIdsByProjectIdIn(sortedProjectIds));
        }
        Map<Long, AccountAuthStateDto> accountStates = accountAuthStatePort.lockAndLoadByIds(affectedUserIds);
        AccountAuthStateDto currentAccount = accountStates.get(userId);
        if (currentAccount == null) {
            return;
        }

        UserProjectReconciliationStateEntity state = reconciliationStateRepository.findById(userId).orElse(null);
        if (state != null && state.getLastReconciledTokenVersion() >= currentAccount.tokenVersion()) {
            log.debug("Skipping already-reconciled lifecycle event={} (eventVer={}, currentVer={}, reconciledVer={})",
                    event.eventId(), event.tokenVersion(), currentAccount.tokenVersion(), state.getLastReconciledTokenVersion());
            return;
        }

        Instant now = Instant.now();
        boolean isCurrentlyActive = isEffectiveActive(currentAccount);

        for (ProjectEntity project : lockedProjects.values()) {
            List<ProjectMemberEntity> members = projectMemberRepository.findMembers(project.getId());
            ProjectMemberEntity targetMembership = members.stream()
                    .filter(m -> m.getUserId().equals(userId))
                    .findFirst()
                    .orElse(null);
            if (targetMembership == null) {
                continue;
            }

            if (!isCurrentlyActive) {
                clearOrphanActiveTasks(project, userId, members, accountStates);
                long rosterVersion = project.bumpRosterVersion();
                publishAfterCommit(new ProjectAccessRevokedEvent(
                        UUID.randomUUID(),
                        project.getId(),
                        userId,
                        rosterVersion,
                        now,
                        AccessRevocationReason.ACCOUNT_SUSPENDED));

                if (project.getStatus() != ProjectStatus.ARCHIVED
                        && targetMembership.getRole() == MemberRole.MANAGER) {
                    boolean hasOtherActiveManager = members.stream()
                            .filter(m -> m.getRole() == MemberRole.MANAGER && !m.getUserId().equals(userId))
                            .anyMatch(m -> isEffectiveActive(accountStates.get(m.getUserId())));
                    if (!hasOtherActiveManager) {
                        UUID attentionId = UUID.randomUUID();
                        project.setNeedsManagerAttention(Boolean.TRUE);
                        project.setManagementAttentionReason("NO_ACTIVE_MANAGER");
                        project.setManagementAttentionSince(now);
                        project.setManagementAttentionEventId(attentionId);

                        attentionAuditRepository.save(ProjectManagementAttentionAuditEntity.builder()
                                .eventId(attentionId)
                                .projectId(project.getId())
                                .suspendedUserId(userId)
                                .userTokenVersion(currentAccount.tokenVersion())
                                .eventType("ATTENTION_REQUIRED")
                                .reasonCode("NO_ACTIVE_MANAGER")
                                .occurredAt(now)
                                .build());

                        publishAfterCommit(new ProjectManagementAttentionRequired(
                                attentionId,
                                project.getId(),
                                userId,
                                now,
                                "NO_ACTIVE_MANAGER"));
                    }
                }
                projectRepository.saveAndFlush(project);
            } else if (Boolean.TRUE.equals(project.getNeedsManagerAttention())) {
                boolean hasAnyActiveManager = members.stream()
                        .filter(m -> m.getRole() == MemberRole.MANAGER)
                        .anyMatch(m -> isEffectiveActive(accountStates.get(m.getUserId())));
                if (hasAnyActiveManager) {
                    project.setNeedsManagerAttention(Boolean.FALSE);
                    project.setManagementAttentionReason(null);
                    project.setManagementAttentionSince(null);
                    project.setManagementAttentionEventId(null);
                    projectRepository.saveAndFlush(project);

                    attentionAuditRepository.save(ProjectManagementAttentionAuditEntity.builder()
                            .eventId(UUID.randomUUID())
                            .projectId(project.getId())
                            .suspendedUserId(userId)
                            .userTokenVersion(currentAccount.tokenVersion())
                            .eventType("ATTENTION_RESOLVED")
                            .reasonCode("MANAGER_REACTIVATED")
                            .occurredAt(now)
                            .build());
                }
            }
        }

        reconciliationStateRepository.saveAndFlush(UserProjectReconciliationStateEntity.builder()
                .userId(userId)
                .lastReconciledTokenVersion(currentAccount.tokenVersion())
                .lastReconciledLifecycle(currentAccount.lifecycle())
                .lastEventId(event.eventId())
                .reconciledAt(now)
                .build());
    }

    private void clearOrphanActiveTasks(
            ProjectEntity project,
            long userId,
            List<ProjectMemberEntity> members,
            Map<Long, AccountAuthStateDto> accountStates) {
        List<TaskEntity> activeAssigned = taskRepository.findActiveAssignedByProjectIdAndAssigneeIdForUpdate(
                project.getId(), userId);
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

        List<ProjectMemberEntity> activeManagers = members.stream()
                .filter(m -> m.getRole() == MemberRole.MANAGER
                        && !m.getUserId().equals(userId)
                        && isEffectiveActive(accountStates.get(m.getUserId())))
                .toList();
        for (ProjectMemberEntity manager : activeManagers) {
            eventPublisher.publishEvent(new ProjectMemberEvent(
                    manager.getUserId(),
                    "Orphaned tasks require reassignment",
                    activeAssigned.size() + " active task(s) in project \"" + project.getName()
                            + "\" were unassigned because the assignee's account was suspended.",
                    "/projects/" + project.getId() + "/board", project.getId()));
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

    public static boolean isEffectiveActive(AccountAuthStateDto account) {
        return account != null && "ACTIVE".equals(account.lifecycle()) && account.emailVerified();
    }
}
