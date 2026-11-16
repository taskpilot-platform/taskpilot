package com.taskpilot.projects.projects.service;

import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.event.ProjectMemberEvent;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectManagementAttentionAuditEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.repository.ProjectManagementAttentionAuditRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.projects.dto.ProjectAttentionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminProjectRecoveryService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectManagementAttentionAuditRepository auditRepository;
    private final AccountAuthStatePort accountAuthStatePort;
    private final UserProfilePort userProfilePort;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<ProjectAttentionResponse> listProjectsNeedingAttention() {
        List<ProjectEntity> projects = projectRepository.findByNeedsManagerAttentionTrueOrderByManagementAttentionSinceAsc();
        List<ProjectAttentionResponse> result = new ArrayList<>();
        for (ProjectEntity project : projects) {
            List<ProjectMemberEntity> members = projectMemberRepository.findMembers(project.getId());
            Set<Long> userIds = members.stream().map(ProjectMemberEntity::getUserId).collect(java.util.stream.Collectors.toSet());
            Map<Long, AccountAuthStateDto> states = accountAuthStatePort.findByIds(userIds);
            Map<Long, UserProfileLiteDto> profiles = userProfilePort.findLiteByIds(userIds).stream()
                    .collect(java.util.stream.Collectors.toMap(UserProfileLiteDto::id, p -> p));

            List<ProjectAttentionResponse.EligibleMember> eligible = members.stream()
                    .filter(m -> {
                        AccountAuthStateDto st = states.get(m.getUserId());
                        return st != null && "ACTIVE".equals(st.lifecycle()) && st.emailVerified();
                    })
                    .map(m -> {
                        AccountAuthStateDto st = states.get(m.getUserId());
                        UserProfileLiteDto prof = profiles.get(m.getUserId());
                        return new ProjectAttentionResponse.EligibleMember(
                                m.getUserId(),
                                st != null ? st.email() : null,
                                prof != null ? prof.fullName() : null,
                                m.getRole() != null ? m.getRole().name() : MemberRole.MEMBER.name());
                    })
                    .toList();

            result.add(new ProjectAttentionResponse(
                    project.getId(),
                    project.getName(),
                    project.getStatus() != null ? project.getStatus().name() : ProjectStatus.ACTIVE.name(),
                    project.getManagementAttentionReason(),
                    project.getManagementAttentionSince(),
                    project.getManagementAttentionEventId(),
                    eligible));
        }
        return result;
    }

    @Transactional
    public void transferProjectManager(long projectId, long targetUserId, long adminUserId, String reason) {
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }

        Map<Long, AccountAuthStateDto> lockedAccounts = accountAuthStatePort.lockAndLoadByIds(List.of(adminUserId, targetUserId));
        AccountAuthStateDto adminState = lockedAccounts.get(adminUserId);
        if (adminState == null || !"ACTIVE".equals(adminState.lifecycle()) || !"ADMIN".equals(adminState.role())) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Active ADMIN role required");
        }

        AccountAuthStateDto targetState = lockedAccounts.get(targetUserId);
        if (targetState == null || !"ACTIVE".equals(targetState.lifecycle()) || !targetState.emailVerified()) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Target member must have an ACTIVE, email-verified account");
        }

        ProjectMemberEntity targetMember = projectMemberRepository.findByProjectIdAndUserId(projectId, targetUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Target user is not a member of this project"));

        targetMember.setRole(MemberRole.MANAGER);
        projectMemberRepository.saveAndFlush(targetMember);

        project.setNeedsManagerAttention(Boolean.FALSE);
        project.setManagementAttentionReason(null);
        project.setManagementAttentionSince(null);
        project.setManagementAttentionEventId(null);
        project.bumpRosterVersion();
        projectRepository.saveAndFlush(project);

        String reasonCode = (reason != null && !reason.isBlank()) ? reason.trim() : "ADMIN_EXPLICIT_TRANSFER";
        if (reasonCode.length() > 64) {
            reasonCode = reasonCode.substring(0, 64);
        }

        auditRepository.saveAndFlush(ProjectManagementAttentionAuditEntity.builder()
                .eventId(UUID.randomUUID())
                .projectId(projectId)
                .eventType("MANAGER_TRANSFERRED")
                .reasonCode(reasonCode)
                .actorUserId(adminUserId)
                .targetUserId(targetUserId)
                .occurredAt(Instant.now())
                .build());

        eventPublisher.publishEvent(new ProjectMemberEvent(
                targetUserId,
                "Project management assigned",
                "An administrator assigned you as Project Manager for \"" + project.getName() + "\".",
                "/projects/" + projectId + "/settings"));
    }
}
