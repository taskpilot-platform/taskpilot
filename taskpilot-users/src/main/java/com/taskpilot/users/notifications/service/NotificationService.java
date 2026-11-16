package com.taskpilot.users.notifications.service;

import com.taskpilot.contracts.project.port.out.ProjectDeliveryAuthorizationPort;
import com.taskpilot.contracts.realtime.RealtimeAccessDecision;
import com.taskpilot.contracts.user.dto.AccountAuthStateDto;
import com.taskpilot.contracts.user.dto.NotificationTypeDto;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.contracts.user.dto.SystemNotificationCommandDto;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.RealtimeSessionAuthorityPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.infrastructure.notification.OneSignalService;
import com.taskpilot.infrastructure.realtime.RealtimeAfterCommitDispatcher;
import com.taskpilot.users.common.entity.NotificationEntity;
import com.taskpilot.users.common.enums.NotificationType;
import com.taskpilot.users.common.repository.NotificationRepository;
import com.taskpilot.users.notifications.dto.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;

@Slf4j
@Service
public class NotificationService {

    private static final String ACTIVE_LIFECYCLE = "ACTIVE";

    private final NotificationRepository notificationRepository;
    private final OneSignalService oneSignalService;
    private final NotificationRealtimeService notificationRealtimeService;
    private final AccountAuthStatePort accountAuthStatePort;
    private final RealtimeSessionAuthorityPort realtimeSessionAuthorityPort;
    private final ObjectProvider<ProjectDeliveryAuthorizationPort> projectDeliveryAuthProvider;

    @Autowired
    public NotificationService(
            NotificationRepository notificationRepository,
            OneSignalService oneSignalService,
            NotificationRealtimeService notificationRealtimeService,
            AccountAuthStatePort accountAuthStatePort,
            RealtimeSessionAuthorityPort realtimeSessionAuthorityPort,
            ObjectProvider<ProjectDeliveryAuthorizationPort> projectDeliveryAuthProvider
    ) {
        this.notificationRepository = notificationRepository;
        this.oneSignalService = oneSignalService;
        this.notificationRealtimeService = notificationRealtimeService;
        this.accountAuthStatePort = accountAuthStatePort;
        this.realtimeSessionAuthorityPort = realtimeSessionAuthorityPort;
        this.projectDeliveryAuthProvider = projectDeliveryAuthProvider;
    }

    public NotificationService(
            NotificationRepository notificationRepository,
            OneSignalService oneSignalService,
            NotificationRealtimeService notificationRealtimeService
    ) {
        this(notificationRepository, oneSignalService, notificationRealtimeService, null, null, null);
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotifications(long userId, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(NotificationResponse::fromEntity);
    }

    @Transactional
    public NotificationResponse getNotificationById(Long notificationId, long userId) {
        NotificationEntity notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Notification not found"));

        if (Boolean.FALSE.equals(notification.getIsRead())) {
            notification.setIsRead(true);
            notification = notificationRepository.save(notification);
            long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
            RealtimeAfterCommitDispatcher.runAfterCommit(
                    () -> notificationRealtimeService.publish(userId, "notification.unread-count", unreadCount));
        }
        return NotificationResponse.fromEntity(notification);
    }

    @Transactional
    public NotificationResponse markAsRead(Long notificationId, long userId) {
        NotificationEntity notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "Notification not found"));

        notification.setIsRead(true);
        NotificationResponse response = NotificationResponse.fromEntity(notificationRepository.save(notification));
        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
        RealtimeAfterCommitDispatcher.runAfterCommit(
                () -> notificationRealtimeService.publish(userId, "notification.unread-count", unreadCount));
        return response;
    }

    @Transactional
    public int markAllAsRead(long userId) {
        int result = notificationRepository.markAllAsReadByUserId(userId);
        RealtimeAfterCommitDispatcher.runAfterCommit(
                () -> notificationRealtimeService.publish(userId, "notification.unread-count", 0L));
        return result;
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(long userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    public SseEmitter streamMyNotifications(RealtimeAccessContext context) {
        if (realtimeSessionAuthorityPort == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Session authority unavailable");
        }
        RealtimeAccessDecision decision = realtimeSessionAuthorityPort.evaluateSession(context);
        if (!decision.allowed()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(),
                    decision.detail() != null ? decision.detail() : "Unauthorized");
        }
        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(context.userId());
        return notificationRealtimeService.subscribe(context, unreadCount);
    }

    @Transactional
    public void createSystemNotification(Long targetUserId, String title, String message, String linkAction) {
        createNotification(new SystemNotificationCommandDto(targetUserId, title, message, linkAction));
    }

    @Transactional
    public NotificationResponse createNotification(SystemNotificationCommandDto command) {
        if (command == null || command.targetUserId() == null || command.targetUserId() <= 0) {
            return null;
        }
        if (!isRecipientEligible(command.projectId(), command.taskId(), command.targetUserId(), false)) {
            log.debug("Skipping notification creation for ineligible recipient userId={} projectId={} taskId={}",
                    command.targetUserId(), command.projectId(), command.taskId());
            return null;
        }

        NotificationEntity notification = NotificationEntity.builder()
                .userId(command.targetUserId())
                .title(command.title())
                .message(command.message())
                .type(toEntityType(command.type()))
                .isRead(false)
                .linkAction(command.linkAction())
                .build();

        NotificationResponse response = NotificationResponse.fromEntity(notificationRepository.save(notification));
        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(command.targetUserId());

        RealtimeAfterCommitDispatcher.runAfterCommit(() -> {
            if (!isRecipientEligible(command.projectId(), command.taskId(), command.targetUserId(), true)) {
                log.debug("Skipping post-commit realtime/push dispatch for revoked recipient userId={} projectId={}",
                        command.targetUserId(), command.projectId());
                return;
            }
            notificationRealtimeService.publishCreated(response, unreadCount, command.projectId());
            oneSignalService.sendNotificationToUser(
                    String.valueOf(command.targetUserId()),
                    command.title(),
                    command.message(),
                    command.linkAction()
            );
        });
        return response;
    }

    private boolean isRecipientEligible(Long projectId, Long taskId, long targetUserId, boolean afterCommit) {
        if (projectId != null) {
            ProjectDeliveryAuthorizationPort port = projectDeliveryAuthProvider != null
                    ? projectDeliveryAuthProvider.getIfAvailable()
                    : null;
            // Fail closed for project-scoped notifications when delivery guard is unavailable.
            return port != null && (afterCommit
                    ? port.canDeliverProjectNotification(projectId, taskId, targetUserId)
                    : port.canCreateProjectNotificationInCurrentTransaction(projectId, taskId, targetUserId));
        }
        // Before persistence we may already hold this user's lock (e.g. AI confirmation).
        // A REQUIRES_NEW guard would wait on our own suspended transaction indefinitely.
        if (!afterCommit && accountAuthStatePort != null) {
            return accountAuthStatePort.findByIdForUpdate(targetUserId)
                    .map(account -> ACTIVE_LIFECYCLE.equals(account.lifecycle()) && account.emailVerified())
                    .orElse(false);
        }
        if (realtimeSessionAuthorityPort != null) {
            return realtimeSessionAuthorityPort.isAccountActiveInNewTx(targetUserId);
        }
        if (accountAuthStatePort != null) {
            Optional<AccountAuthStateDto> account = accountAuthStatePort.findById(targetUserId);
            return account.isPresent() && account.get().emailVerified() && ACTIVE_LIFECYCLE.equals(account.get().lifecycle());
        }
        return true;
    }

    private NotificationType toEntityType(NotificationTypeDto type) {
        return NotificationType.valueOf(
                (type != null ? type : NotificationTypeDto.SYSTEM).name());
    }
}
