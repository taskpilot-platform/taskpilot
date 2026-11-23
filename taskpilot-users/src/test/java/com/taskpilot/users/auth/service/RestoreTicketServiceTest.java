package com.taskpilot.users.auth.service;

import com.taskpilot.contracts.user.dto.SecurityChangeReason;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.auth.dto.AuthResponse;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.AccountLifecycle;
import com.taskpilot.users.common.enums.EmailTokenPurpose;
import com.taskpilot.users.common.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestoreTicketServiceTest {

    @Mock EmailChallengeService emailChallengeService;
    @Mock UserSessionService userSessionService;

    private RestoreTicketService service;

    @BeforeEach
    void setUp() {
        service = new RestoreTicketService(emailChallengeService, userSessionService);
    }

    @Test
    void issueRestoreTicketRejectsNonSelfDeactivatedStates() {
        UserEntity adminSuspended = UserEntity.builder().id(1L).accountState(AccountLifecycle.ADMIN_SUSPENDED).build();
        assertThatThrownBy(() -> service.issueRestoreTicket(adminSuspended))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(403));

        UserEntity active = UserEntity.builder().id(2L).accountState(AccountLifecycle.ACTIVE).build();
        assertThatThrownBy(() -> service.issueRestoreTicket(active))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(403));
        verifyNoInteractions(emailChallengeService);
    }

    @SuppressWarnings("unchecked")
    @Test
    void confirmRestoreRejectsAdminSuspendedAndPreservesOooAvailabilityUponValidRestore() {
        UserEntity suspended = UserEntity.builder()
                .id(10L).accountState(AccountLifecycle.ADMIN_SUSPENDED).emailVerified(true).build();
        doAnswer(inv -> {
            Consumer<UserEntity> mutator = inv.getArgument(4);
            mutator.accept(suspended);
            return null;
        }).when(emailChallengeService).consume(eq("tok-susp"), eq(EmailTokenPurpose.RESTORE), isNull(), isNull(), any());

        assertThatThrownBy(() -> service.confirmRestore("tok-susp"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(403));
        verifyNoInteractions(userSessionService);

        UserEntity selfDeactOoo = UserEntity.builder()
                .id(11L).accountState(AccountLifecycle.SELF_DEACTIVATED).status(UserStatus.OOO).emailVerified(true).build();
        doAnswer(inv -> {
            Consumer<UserEntity> mutator = inv.getArgument(4);
            mutator.accept(selfDeactOoo);
            return new EmailChallengeService.ConsumedChallenge(11L, EmailTokenPurpose.RESTORE, null, "hash");
        }).when(emailChallengeService).consume(eq("tok-ok"), eq(EmailTokenPurpose.RESTORE), isNull(), isNull(), any());

        AuthResponse issued = new AuthResponse("jwt", "ref", "Bearer", 900L);
        when(userSessionService.issueSession(11L)).thenReturn(issued);

        AuthResponse result = service.confirmRestore("tok-ok");
        assertThat(result).isEqualTo(issued);
        assertThat(selfDeactOoo.getAccountState()).isEqualTo(AccountLifecycle.ACTIVE);
        assertThat(selfDeactOoo.getStatus()).isEqualTo(UserStatus.OOO);
        verify(userSessionService).invalidateAll(11L, SecurityChangeReason.USER_RESTORED);
    }

    @SuppressWarnings("unchecked")
    @Test
    void cancelRestoreConsumesTicketWithoutReactivatingAccount() {
        UserEntity selfDeact = UserEntity.builder()
                .id(12L).accountState(AccountLifecycle.SELF_DEACTIVATED).emailVerified(true).build();
        doAnswer(inv -> {
            Consumer<UserEntity> mutator = inv.getArgument(4);
            mutator.accept(selfDeact);
            return new EmailChallengeService.ConsumedChallenge(12L, EmailTokenPurpose.RESTORE, null, "hash");
        }).when(emailChallengeService).consume(eq("tok-cancel"), eq(EmailTokenPurpose.RESTORE), isNull(), isNull(), any());

        service.cancelRestore("tok-cancel");
        assertThat(selfDeact.getAccountState()).isEqualTo(AccountLifecycle.SELF_DEACTIVATED);
        verifyNoInteractions(userSessionService);
    }
}
