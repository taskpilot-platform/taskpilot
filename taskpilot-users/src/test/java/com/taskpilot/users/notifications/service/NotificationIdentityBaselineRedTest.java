package com.taskpilot.users.notifications.service;

import com.taskpilot.users.common.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies NotificationService operates directly on immutable numeric userId without email cache.
 */
@ExtendWith(MockitoExtension.class)
class NotificationIdentityBaselineRedTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private NotificationPushDispatcher pushDispatcher;
    @Mock
    private NotificationRealtimeService notificationRealtimeService;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                notificationRepository, pushDispatcher, notificationRealtimeService);
    }

    @Test
    void getMyNotifications_usesDirectNumericUserIdAcrossCalls() {
        Pageable pageable = PageRequest.of(0, 10);
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(1L, pageable)).thenReturn(Page.empty(pageable));
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(2L, pageable)).thenReturn(Page.empty(pageable));

        notificationService.getMyNotifications(1L, pageable);
        notificationService.getMyNotifications(2L, pageable);

        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(eq(1L), eq(pageable));
        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(eq(2L), eq(pageable));
    }

    @Test
    void getUnreadCount_queriesDistinctNumericUserIdDirectly() {
        when(notificationRepository.countByUserIdAndIsReadFalse(2L)).thenReturn(4L);

        long unread = notificationService.getUnreadCount(2L);

        assertThat(unread).isEqualTo(4L);
        verify(notificationRepository).countByUserIdAndIsReadFalse(2L);
        verify(notificationRepository, never()).countByUserIdAndIsReadFalse(1L);
    }
}
