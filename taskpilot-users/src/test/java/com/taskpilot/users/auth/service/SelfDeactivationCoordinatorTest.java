package com.taskpilot.users.auth.service;

import com.taskpilot.contracts.project.exception.RetryableRosterChangeException;
import com.taskpilot.contracts.project.port.out.ProjectAccountLifecycleGuard;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.profile.dto.SelfDeactivateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SelfDeactivationCoordinatorTest {

    @SuppressWarnings("unchecked")
    @Test
    void retriesFreshAttemptsUpToThreeTimesAndExpandsLockSetOnRosterChange() {
        ProjectAccountLifecycleGuard guard = mock(ProjectAccountLifecycleGuard.class);
        when(guard.findCandidateManagerialProjectIds(7L))
                .thenReturn(List.of(10L))
                .thenReturn(List.of(10L, 25L));

        ObjectProvider<ProjectAccountLifecycleGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);

        SelfDeactivationAttempt attemptExecutor = mock(SelfDeactivationAttempt.class);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new RetryableRosterChangeException(Set.of(25L));
            }
            return null;
        }).when(attemptExecutor).executeAttempt(eq(7L), anyList(), any());

        SelfDeactivationCoordinator coordinator = new SelfDeactivationCoordinator(provider, attemptExecutor);
        coordinator.selfDeactivate(7L, new SelfDeactivateRequest("DEACTIVATE", "Pass1234", null));

        verify(attemptExecutor).executeAttempt(eq(7L), eq(List.of(10L)), any());
        verify(attemptExecutor).executeAttempt(eq(7L), eq(List.of(10L, 25L)), any());
        assertThat(calls.get()).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    @Test
    void failClosedOnNullSelfDeactivationRequestInCoordinatorAndAttempt() {
        ObjectProvider<ProjectAccountLifecycleGuard> provider = mock(ObjectProvider.class);
        SelfDeactivationAttempt attemptExecutor = mock(SelfDeactivationAttempt.class);
        SelfDeactivationCoordinator coordinator = new SelfDeactivationCoordinator(provider, attemptExecutor);

        assertThatThrownBy(() -> coordinator.selfDeactivate(9L, null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(400));
        verifyNoInteractions(attemptExecutor);

        var userRepo = mock(com.taskpilot.users.common.repository.UserRepository.class);
        var sessionSvc = mock(UserSessionService.class);
        var linkSvc = mock(com.taskpilot.users.auth.oauth2.CredentialLinkService.class);
        var activeUser = com.taskpilot.users.common.entity.UserEntity.builder()
                .id(9L)
                .email("u9@example.com")
                .accountState(com.taskpilot.users.common.enums.AccountLifecycle.ACTIVE)
                .emailVerified(true)
                .build();
        when(userRepo.findByIdForUpdate(9L)).thenReturn(java.util.Optional.of(activeUser));

        SelfDeactivationAttempt realAttempt = new SelfDeactivationAttempt(provider, userRepo, sessionSvc, linkSvc);
        assertThatThrownBy(() -> realAttempt.executeAttempt(9L, List.of(), null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(400));
        assertThat(activeUser.getAccountState()).isEqualTo(com.taskpilot.users.common.enums.AccountLifecycle.ACTIVE);
        verify(userRepo, never()).saveAndFlush(any());
        verifyNoInteractions(sessionSvc);
    }

    @SuppressWarnings("unchecked")
    @Test
    void failsCleanlyWith409AfterThreeRetryableConflicts() {
        ProjectAccountLifecycleGuard guard = mock(ProjectAccountLifecycleGuard.class);
        when(guard.findCandidateManagerialProjectIds(8L)).thenReturn(List.of(1L));

        ObjectProvider<ProjectAccountLifecycleGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);

        SelfDeactivationAttempt attemptExecutor = mock(SelfDeactivationAttempt.class);
        doThrow(new RetryableRosterChangeException(Set.of(2L)))
                .when(attemptExecutor).executeAttempt(eq(8L), anyList(), any());

        SelfDeactivationCoordinator coordinator = new SelfDeactivationCoordinator(provider, attemptExecutor);

        assertThatThrownBy(() -> coordinator.selfDeactivate(8L, new SelfDeactivateRequest("DEACTIVATE", "Pass1234", null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(409));

        verify(attemptExecutor, times(SelfDeactivationCoordinator.MAX_ATTEMPTS))
                .executeAttempt(eq(8L), anyList(), any());
    }
}
