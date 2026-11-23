package com.taskpilot.projects.realtime.adapter;

import com.taskpilot.contracts.project.port.out.ProjectDeliveryAuthorizationPort;
import com.taskpilot.contracts.realtime.RealtimeAccessDecision;
import com.taskpilot.contracts.realtime.RealtimeAccessDenyReason;
import com.taskpilot.contracts.realtime.RealtimeAccessPort;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.RealtimeSessionAuthorityPort;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Projects-owned realtime and notification delivery guard.
 * Enforces canonical lock order (projects -> users -> refresh_tokens) and fails closed.
 */
@Component
@RequiredArgsConstructor
public class ProjectRealtimeAccessAdapter implements RealtimeAccessPort, ProjectDeliveryAuthorizationPort {

    private static final String ACTIVE_LIFECYCLE = "ACTIVE";

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final TaskRepository taskRepository;
    private final RealtimeSessionAuthorityPort realtimeSessionAuthorityPort;
    private final AccountAuthStatePort accountAuthStatePort;

    @Override
    @Transactional(readOnly = true)
    public boolean isSessionCurrent(RealtimeAccessContext context) {
        return evaluateSession(context).allowed();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean canReceiveProject(long projectId, RealtimeAccessContext context) {
        return evaluateProjectAccess(projectId, context).allowed();
    }

    @Override
    @Transactional(readOnly = true)
    public RealtimeAccessDecision evaluateSession(RealtimeAccessContext context) {
        if (realtimeSessionAuthorityPort == null) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.SESSION_REVOKED, "Session authority unavailable");
        }
        return realtimeSessionAuthorityPort.evaluateSession(context);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RealtimeAccessDecision evaluateProjectAccess(long projectId, RealtimeAccessContext context) {
        if (projectId <= 0) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED, "Invalid project");
        }
        if (context == null || context.userId() <= 0) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.UNAUTHENTICATED, "Missing realtime session context");
        }
        if (realtimeSessionAuthorityPort == null) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.SESSION_REVOKED, "Session authority unavailable");
        }

        // Canonical lock order: project -> user -> session.
        Optional<ProjectEntity> projectOpt = projectRepository.findByIdForUpdate(projectId);
        if (projectOpt.isEmpty()) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED, "Project not found");
        }

        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, context.userId())) {
            return RealtimeAccessDecision.deny(RealtimeAccessDenyReason.PROJECT_ACCESS_REVOKED, "Not a project member");
        }

        return realtimeSessionAuthorityPort.evaluateSessionForUpdate(context);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean canDeliverProjectNotification(long projectId, Long taskId, long targetUserId) {
        return evaluateNotificationRecipient(projectId, taskId, targetUserId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean canCreateProjectNotificationInCurrentTransaction(long projectId, Long taskId, long targetUserId) {
        return evaluateNotificationRecipient(projectId, taskId, targetUserId);
    }

    private boolean evaluateNotificationRecipient(long projectId, Long taskId, long targetUserId) {
        if (projectId <= 0 || targetUserId <= 0) {
            return false;
        }
        // Canonical lock order: project -> user.
        if (projectRepository.findByIdForUpdate(projectId).isEmpty()) {
            return false;
        }
        if (taskId != null) {
            TaskEntity task = taskRepository.findById(taskId).orElse(null);
            if (task == null || task.isDeleted() || !Objects.equals(task.getProjectId(), projectId)) {
                return false;
            }
        }
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, targetUserId)) {
            return false;
        }
        if (accountAuthStatePort == null) {
            return false;
        }
        AccountAuthStateDto account = accountAuthStatePort.findByIdForUpdate(targetUserId).orElse(null);
        return account != null && account.emailVerified() && ACTIVE_LIFECYCLE.equals(account.lifecycle());
    }
}
