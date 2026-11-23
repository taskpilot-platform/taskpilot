package com.taskpilot.users.auth.service;

import com.taskpilot.contracts.project.exception.RetryableRosterChangeException;
import com.taskpilot.contracts.project.port.out.ProjectAccountLifecycleGuard;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.profile.dto.SelfDeactivateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Nontransactional outer coordinator for self-deactivation.
 * Never opens an enclosing transaction; each retry runs in a completely fresh transaction
 * inside {@link SelfDeactivationAttempt} up to {@value #MAX_ATTEMPTS} attempts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfDeactivationCoordinator {

    public static final int MAX_ATTEMPTS = 3;

    private final ObjectProvider<ProjectAccountLifecycleGuard> lifecycleGuardProvider;
    private final SelfDeactivationAttempt selfDeactivationAttempt;

    @Transactional(propagation = Propagation.NEVER)
    public void selfDeactivate(long userId, SelfDeactivateRequest request) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Explicit self-deactivation confirmation and credential proof are required");
        }
        ProjectAccountLifecycleGuard guard = lifecycleGuardProvider.getIfAvailable();
        Set<Long> candidateProjectIds = new LinkedHashSet<>();
        if (guard != null) {
            candidateProjectIds.addAll(guard.findCandidateManagerialProjectIds(userId));
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                selfDeactivationAttempt.executeAttempt(userId, List.copyOf(candidateProjectIds), request);
                return;
            } catch (RetryableRosterChangeException rosterEx) {
                log.info("Self-deactivation attempt {}/{} rolled back due to newly discovered managerial projects for userId={}",
                        attempt, MAX_ATTEMPTS, userId);
                candidateProjectIds.addAll(rosterEx.getMissingProjectIds());
                if (guard != null) {
                    candidateProjectIds.addAll(guard.findCandidateManagerialProjectIds(userId));
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new BusinessException(HttpStatus.CONFLICT.value(),
                            "Concurrent project roster changes prevented self-deactivation after "
                                    + MAX_ATTEMPTS + " attempts. Please retry.");
                }
            } catch (RuntimeException ex) {
                if (!isTransientLockOrSerializationFailure(ex)) {
                    throw ex;
                }
                log.info("Self-deactivation attempt {}/{} rolled back due to transient lock/serialization conflict for userId={}",
                        attempt, MAX_ATTEMPTS, userId);
                if (guard != null) {
                    candidateProjectIds.addAll(guard.findCandidateManagerialProjectIds(userId));
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new BusinessException(HttpStatus.CONFLICT.value(),
                            "Account self-deactivation conflicted with concurrent operations. Please retry.");
                }
                pauseBriefly(attempt);
            }
        }
    }

    private static boolean isTransientLockOrSerializationFailure(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof ConcurrencyFailureException) {
                return true;
            }
            if (cur instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if ("40001".equals(state) || "40P01".equals(state) || "55P03".equals(state)) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static void pauseBriefly(int attempt) {
        try {
            Thread.sleep(15L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
