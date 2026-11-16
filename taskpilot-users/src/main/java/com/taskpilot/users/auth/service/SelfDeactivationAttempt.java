package com.taskpilot.users.auth.service;

import com.taskpilot.contracts.project.port.out.ProjectAccountLifecycleGuard;
import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.oauth2.CredentialLinkService;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.repository.UserRepository;
import com.taskpilot.users.profile.dto.SelfDeactivateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes a single fresh transaction attempt for self-deactivation.
 * Enforces canonical lock order: projects (ascending) -> users (ascending) -> rescan guard -> state mutation.
 */
@Service
@RequiredArgsConstructor
public class SelfDeactivationAttempt {

    private final ObjectProvider<ProjectAccountLifecycleGuard> lifecycleGuardProvider;
    private final UserRepository userRepository;
    private final UserSessionService userSessionService;
    private final CredentialLinkService credentialLinkService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeAttempt(
            long userId,
            List<Long> candidateProjectIds,
            SelfDeactivateRequest request) {
        List<Long> sortedProjectIds = candidateProjectIds == null
                ? List.of()
                : candidateProjectIds.stream().filter(id -> id != null && id > 0).distinct().sorted().toList();

        ProjectAccountLifecycleGuard guard = lifecycleGuardProvider.getIfAvailable();
        Set<Long> affectedUserIds = new LinkedHashSet<>();
        affectedUserIds.add(userId);
        if (guard != null && !sortedProjectIds.isEmpty()) {
            affectedUserIds.addAll(guard.lockProjectsAndFindAffectedUserIds(sortedProjectIds));
        }

        // Acquire all affected user rows in strict ascending ID order after project locks.
        List<Long> sortedUserIds = new ArrayList<>(affectedUserIds);
        sortedUserIds.sort(Long::compareTo);
        UserEntity lockedTargetUser = null;
        for (Long affectedId : sortedUserIds) {
            UserEntity locked = userRepository.findByIdForUpdate(affectedId).orElse(null);
            if (locked != null && affectedId.equals(userId)) {
                lockedTargetUser = locked;
            }
        }
        if (lockedTargetUser == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "User not found");
        }

        if (!lockedTargetUser.isActiveAccount() || !Boolean.TRUE.equals(lockedTargetUser.getEmailVerified())) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "Account is not active");
        }

        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Explicit self-deactivation confirmation and credential proof are required");
        }
        validateConfirmation(request.confirmation());
        credentialLinkService.verifyExistingCredentialProof(
                lockedTargetUser, request.currentPassword(), request.reauthTicket());

        // Rescan managerial projects and verify no sole-active-manager invariant is violated.
        if (guard != null) {
            guard.assertCanSelfDeactivate(userId, Set.copyOf(sortedProjectIds));
        }

        // Preserve email, uid, identities, password hash, and memberships; transition lifecycle only.
        lockedTargetUser.setAccountState(AccountLifecycle.SELF_DEACTIVATED);
        lockedTargetUser.setLifecycleChangedAt(Instant.now());
        lockedTargetUser.setLifecycleReason("USER_SELF_DEACTIVATED");
        lockedTargetUser.setLifecycleChangedBy(userId);
        userRepository.saveAndFlush(lockedTargetUser);

        userSessionService.invalidateAll(userId, SecurityChangeReason.USER_DELETED);
    }

    private static void validateConfirmation(String confirmation) {
        if (confirmation == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Explicit self-deactivation confirmation is required");
        }
        String normalized = confirmation.trim();
        if (!"DEACTIVATE".equalsIgnoreCase(normalized)
                && !"CONFIRM".equalsIgnoreCase(normalized)
                && !"true".equalsIgnoreCase(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Explicit self-deactivation confirmation is required");
        }
    }
}
