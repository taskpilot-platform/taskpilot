package com.taskpilot.projects.projects.service;

import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.UserIdentityDto;
import com.taskpilot.contracts.user.event.ProjectMemberEvent;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.UserIdentityPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.ProjectInvitationEntity;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.enums.MemberRole;
import com.taskpilot.projects.common.enums.ProjectStatus;
import com.taskpilot.projects.common.repository.ProjectInvitationRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.projects.dto.ProjectInvitationCreatedResponse;
import com.taskpilot.projects.projects.dto.ProjectInvitationResponse;
import com.taskpilot.projects.projects.dto.ProjectMemberResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Copy-share private project invitation lifecycle with 32-byte CSPRNG tokens,
 * SHA-256 storage, verified email binding, 7-day TTL, and canonical lock ordering
 * ({@code projects -> users -> project_invitations}).
 */
@Slf4j
@Service
public class ProjectInvitationService {

    public static final Duration INVITATION_TTL = Duration.ofDays(7);
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${application.projects.invitation-accept-url:http://localhost:5173/invitations/accept}")
    private String invitationAcceptBaseUrl = "http://localhost:5173/invitations/accept";

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectInvitationRepository invitationRepository;
    private final AccountAuthStatePort accountAuthStatePort;
    private final UserIdentityPort userIdentityPort;
    private final UserPort userPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public ProjectInvitationService(
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectInvitationRepository invitationRepository,
            AccountAuthStatePort accountAuthStatePort,
            UserIdentityPort userIdentityPort,
            UserPort userPort,
            ApplicationEventPublisher eventPublisher) {
        this(projectRepository, projectMemberRepository, invitationRepository,
                accountAuthStatePort, userIdentityPort, userPort, eventPublisher, Clock.systemUTC());
    }

    public ProjectInvitationService(
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectInvitationRepository invitationRepository,
            AccountAuthStatePort accountAuthStatePort,
            UserIdentityPort userIdentityPort,
            UserPort userPort,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.invitationRepository = invitationRepository;
        this.accountAuthStatePort = accountAuthStatePort;
        this.userIdentityPort = userIdentityPort;
        this.userPort = userPort;
        this.eventPublisher = eventPublisher;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional
    public ProjectInvitationCreatedResponse createInvitation(long projectId, String rawEmail, long managerUserId) {
        String inviteeEmail = normalizeEmail(rawEmail);
        if (inviteeEmail.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invitee email is required");
        }

        // Canonical lock order: project -> affected users -> pending invitations
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }

        Optional<UserIdentityDto> existingInvitee = userIdentityPort.findByEmail(inviteeEmail);
        List<Long> lockUserIds = existingInvitee
                .map(idDto -> List.of(managerUserId, idDto.id()))
                .orElseGet(() -> List.of(managerUserId));
        Map<Long, AccountAuthStateDto> accounts = accountAuthStatePort.lockAndLoadByIds(lockUserIds);

        assertActiveManager(projectId, managerUserId, accounts.get(managerUserId));

        if (existingInvitee.isPresent()
                && projectMemberRepository.existsByProjectIdAndUserId(projectId, existingInvitee.get().id())) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "User with this email is already a member of this project");
        }

        Instant now = clock.instant();
        List<ProjectInvitationEntity> priorPending = invitationRepository.findPendingByProjectIdAndEmailForUpdate(
                projectId, inviteeEmail);
        for (ProjectInvitationEntity old : priorPending) {
            old.setRevokedAt(now);
            old.setRevokedByUserId(managerUserId);
            old.setRevocationReason("REISSUED");
            invitationRepository.save(old);
        }
        if (!priorPending.isEmpty()) {
            invitationRepository.flush();
        }

        String rawToken = generateToken();
        String tokenHash = sha256Hex(rawToken);
        Instant expiresAt = now.plus(INVITATION_TTL);

        ProjectInvitationEntity created = ProjectInvitationEntity.builder()
                .projectId(projectId)
                .inviteeEmail(inviteeEmail)
                .tokenHash(tokenHash)
                .invitedByUserId(managerUserId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        created = invitationRepository.saveAndFlush(created);

        log.info("Project invitation issued: invitationId={}, projectId={}, invitedBy={}",
                created.getId(), projectId, managerUserId);

        String inviteUrl = invitationAcceptBaseUrl + "?token=" + rawToken;
        return new ProjectInvitationCreatedResponse(
                created.getId(),
                projectId,
                inviteeEmail,
                created.getIssuedAt(),
                created.getExpiresAt(),
                rawToken,
                inviteUrl);
    }

    @Transactional(readOnly = true)
    public List<ProjectInvitationResponse> listInvitations(long projectId, long managerUserId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        AccountAuthStateDto actorState = accountAuthStatePort.findById(managerUserId).orElse(null);
        assertActiveManager(projectId, managerUserId, actorState);

        Instant now = clock.instant();
        return invitationRepository.findByProjectIdOrderByIssuedAtDesc(projectId).stream()
                .map(inv -> ProjectInvitationResponse.fromEntity(inv, now))
                .toList();
    }

    @Transactional
    public void revokeInvitation(long projectId, long invitationId, long managerUserId) {
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }

        Map<Long, AccountAuthStateDto> accounts = accountAuthStatePort.lockAndLoadByIds(List.of(managerUserId));
        assertActiveManager(projectId, managerUserId, accounts.get(managerUserId));

        ProjectInvitationEntity invitation = invitationRepository.findByIdAndProjectIdForUpdate(invitationId, projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Invitation not found"));

        if (invitation.getConsumedAt() != null) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Invitation has already been accepted");
        }
        if (invitation.getRevokedAt() == null) {
            invitation.setRevokedAt(clock.instant());
            invitation.setRevokedByUserId(managerUserId);
            invitation.setRevocationReason("REVOKED_BY_MANAGER");
            invitationRepository.saveAndFlush(invitation);
            log.info("Project invitation revoked: invitationId={}, projectId={}, actor={}",
                    invitationId, projectId, managerUserId);
        }
    }

    @Transactional
    public ProjectMemberResponse acceptInvitation(String rawToken, long actorUserId) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Invitation token is required");
        }
        String tokenHash = sha256Hex(rawToken.trim());

        Long projectId = invitationRepository.findProjectIdByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Invalid or expired invitation token"));

        // Canonical lock order: project -> user -> invitation
        ProjectEntity project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Project not found"));

        Map<Long, AccountAuthStateDto> accounts = accountAuthStatePort.lockAndLoadByIds(List.of(actorUserId));
        AccountAuthStateDto actor = accounts.get(actorUserId);

        ProjectInvitationEntity invitation = invitationRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Invalid or expired invitation token"));

        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Project is archived");
        }
        if (actor == null || !"ACTIVE".equals(actor.lifecycle()) || !actor.emailVerified()) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Your account must be active and email-verified to accept a project invitation");
        }

        String actorEmail = normalizeEmail(actor.email());
        if (!invitation.getInviteeEmail().equals(actorEmail)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "This invitation was issued for a different email address");
        }

        Instant now = clock.instant();
        if (invitation.getConsumedAt() != null) {
            if (Long.valueOf(actorUserId).equals(invitation.getConsumedByUserId())) {
                Optional<ProjectMemberEntity> currentMembership =
                        projectMemberRepository.findByProjectIdAndUserId(projectId, actorUserId);
                if (currentMembership.isPresent()) {
                    // Idempotent same-consumer retry while still an active project member.
                    return ProjectMemberResponse.fromEntity(currentMembership.get());
                }
                throw new BusinessException(HttpStatus.CONFLICT.value(),
                        "Invitation already used prior to project removal; obtain a fresh invitation from a manager");
            }
            throw new BusinessException(HttpStatus.CONFLICT.value(), "Invitation has already been accepted");
        }

        if (invitation.getRevokedAt() != null) {
            throw new BusinessException(HttpStatus.GONE.value(),
                    "This invitation has been revoked");
        }
        if (invitation.getExpiresAt() == null || !invitation.getExpiresAt().isAfter(now)) {
            throw new BusinessException(HttpStatus.GONE.value(),
                    "This invitation has expired");
        }

        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, actorUserId)) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "You are already a member of this project");
        }

        invitation.setConsumedAt(now);
        invitation.setConsumedByUserId(actorUserId);
        invitationRepository.saveAndFlush(invitation);

        ProjectMemberEntity member = ProjectMemberEntity.builder()
                .projectId(projectId)
                .project(project)
                .userId(actorUserId)
                .role(MemberRole.MEMBER)
                .build();
        member = projectMemberRepository.saveAndFlush(member);

        project.bumpRosterVersion();
        projectRepository.saveAndFlush(project);

        String joinedName = userPort.findById(actorUserId)
                .map(u -> u.fullName())
                .orElse("A member");
        List<ProjectMemberEntity> managers = projectMemberRepository.findByProjectIdAndRole(projectId, MemberRole.MANAGER);
        for (ProjectMemberEntity manager : managers) {
            if (!manager.getUserId().equals(actorUserId)) {
                eventPublisher.publishEvent(new ProjectMemberEvent(
                        manager.getUserId(),
                        "New member joined your project",
                        joinedName + " accepted an invitation and joined project \"" + project.getName() + "\".",
                        "/projects/" + projectId + "/settings", projectId));
            }
        }

        log.info("Project invitation accepted: invitationId={}, projectId={}, userId={}",
                invitation.getId(), projectId, actorUserId);
        return ProjectMemberResponse.fromEntity(member);
    }

    private void assertActiveManager(long projectId, long userId, AccountAuthStateDto state) {
        if (state == null || !"ACTIVE".equals(state.lifecycle())) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account is not active");
        }
        ProjectMemberEntity member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN.value(),
                        "You are not a member of this project"));
        if (member.getRole() != MemberRole.MANAGER) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "Only Project Manager can perform this action");
        }
    }

    private static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
