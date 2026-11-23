package com.taskpilot.infrastructure.storage.cleanup;

import com.taskpilot.contracts.storage.StorageCleanupPort;
import com.taskpilot.contracts.storage.StorageCleanupRequest;
import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.contracts.storage.StorageTargetDomain;
import com.taskpilot.contracts.storage.StorageUploadOperationDto;
import com.taskpilot.contracts.storage.StorageUploadOperationStatus;
import com.taskpilot.contracts.storage.StorageUploadReservationCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class B2UploadReservationReconciliationTest {

    @Mock
    private StorageUploadOperationRepository repository;
    @Mock
    private StorageCleanupPort cleanupPort;

    private StorageUploadOperationService service;

    @BeforeEach
    void setUp() {
        service = new StorageUploadOperationService(repository, cleanupPort, null, null, 600L);
    }

    @Test
    void expiredReservedB2OperationReconcilesPlannedObjectKeyIntoDurableB2Cleanup() {
        UUID opId = UUID.randomUUID();
        StorageUploadOperationEntity expiredB2 = StorageUploadOperationEntity.builder()
                .operationId(opId)
                .ownerUserId(7L)
                .projectId(10L)
                .targetDomain(StorageTargetDomain.PROJECT_FILE)
                .provider(StorageProviderKind.B2)
                .bucket("taskpilot-recordings")
                .plannedObjectKey("recordings/10/5/abandoned.webm")
                .providerObjectKey(null)
                .originalFilename("abandoned.webm")
                .contentType("video/webm")
                .expectedSize(0L)
                .status(StorageUploadOperationStatus.RESERVED)
                .leaseUntil(Instant.now().minusSeconds(60))
                .createdAt(Instant.now().minusSeconds(6000))
                .updatedAt(Instant.now().minusSeconds(6000))
                .build();

        when(repository.findExpiredOperations(any(), any(), any(PageRequest.class))).thenReturn(List.of(expiredB2));
        when(repository.findByIdForUpdate(opId)).thenReturn(Optional.of(expiredB2));
        when(repository.saveAndFlush(any(StorageUploadOperationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        int reconciled = service.reconcileExpiredOperations(10);

        assertThat(reconciled).isEqualTo(1);
        ArgumentCaptor<StorageCleanupRequest> reqCap = ArgumentCaptor.forClass(StorageCleanupRequest.class);
        verify(cleanupPort).enqueueCleanup(reqCap.capture());
        assertThat(reqCap.getValue().locator())
                .isEqualTo(new StorageObjectLocator(StorageProviderKind.B2, "taskpilot-recordings", "recordings/10/5/abandoned.webm"));
        assertThat(expiredB2.getStatus()).isEqualTo(StorageUploadOperationStatus.COMPENSATION_QUEUED);
    }

    @Test
    void customPerOperationLeaseAppliesOnlyToExplicitReservationAndNeverShortensOnProviderWritten() {
        when(repository.saveAndFlush(any(StorageUploadOperationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        StorageUploadOperationDto b2Op = service.reserve(new StorageUploadReservationCommand(
                7L, 10L, StorageTargetDomain.PROJECT_FILE, StorageProviderKind.B2,
                "taskpilot-recordings", "recordings/10/5/long.webm", "long.webm", "video/webm", 0L,
                Duration.ofMinutes(95)
        ));
        StorageUploadOperationDto defaultS3Op = service.reserve(new StorageUploadReservationCommand(
                7L, 10L, StorageTargetDomain.PROJECT_FILE, StorageProviderKind.S3,
                "documents", "projects/10/files/doc.pdf", "doc.pdf", "application/pdf", 1024L
        ));

        assertThat(b2Op.leaseUntil()).isAfter(before.plus(Duration.ofMinutes(90)));
        assertThat(defaultS3Op.leaseUntil()).isBefore(before.plus(Duration.ofMinutes(15)));

        // markProviderWritten must NOT shorten the 95-minute B2 lease down to the 10-minute default.
        StorageUploadOperationEntity stored = StorageUploadOperationEntity.builder()
                .operationId(b2Op.operationId())
                .ownerUserId(7L)
                .projectId(10L)
                .targetDomain(StorageTargetDomain.PROJECT_FILE)
                .provider(StorageProviderKind.B2)
                .bucket("taskpilot-recordings")
                .plannedObjectKey("recordings/10/5/long.webm")
                .status(StorageUploadOperationStatus.RESERVED)
                .leaseUntil(b2Op.leaseUntil())
                .build();
        when(repository.findByIdForUpdate(b2Op.operationId())).thenReturn(Optional.of(stored));

        StorageUploadOperationDto written = service.markProviderWritten(b2Op.operationId(), "recordings/10/5/long.webm");
        assertThat(written.leaseUntil()).isAfterOrEqualTo(b2Op.leaseUntil());
    }

    @Test
    void completeVsExpiryRaceHasSingleOwnershipOutcome() {
        UUID opId = UUID.randomUUID();
        StorageUploadOperationEntity attached = StorageUploadOperationEntity.builder()
                .operationId(opId)
                .ownerUserId(7L)
                .projectId(10L)
                .targetDomain(StorageTargetDomain.PROJECT_FILE)
                .provider(StorageProviderKind.B2)
                .bucket("taskpilot-recordings")
                .plannedObjectKey("recordings/10/5/attached.webm")
                .providerObjectKey("recordings/10/5/attached.webm")
                .status(StorageUploadOperationStatus.ATTACHED)
                .attachedEntityId(901L)
                .leaseUntil(Instant.now().minusSeconds(120))
                .build();
        when(repository.findByIdForUpdate(opId)).thenReturn(Optional.of(attached));

        service.compensateFailedUpload(opId, null, "UPLOAD_LEASE_EXPIRED", "Late expiry check");
        verify(cleanupPort, never()).enqueueCleanup(any());

        // Conversely, if compensation already took ownership, attach MUST fail closed.
        attached.setStatus(StorageUploadOperationStatus.COMPENSATION_QUEUED);
        assertThatThrownBy(() -> service.markAttachedInCurrentTransaction(opId, 901L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deferredCompensationSchedulesCleanupAfterPutWriteWindow() {
        UUID opId = UUID.randomUUID();
        StorageUploadOperationEntity reserved = StorageUploadOperationEntity.builder()
                .operationId(opId)
                .ownerUserId(7L)
                .projectId(10L)
                .targetDomain(StorageTargetDomain.PROJECT_FILE)
                .provider(StorageProviderKind.B2)
                .bucket("taskpilot-recordings")
                .plannedObjectKey("recordings/10/5/rejected.webm")
                .status(StorageUploadOperationStatus.RESERVED)
                .leaseUntil(Instant.now().plus(Duration.ofMinutes(90)))
                .build();
        when(repository.findByIdForUpdate(opId)).thenReturn(Optional.of(reserved));
        when(repository.saveAndFlush(any(StorageUploadOperationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant notBefore = Instant.now().plus(Duration.ofMinutes(20));
        service.compensateFailedUpload(
                opId,
                new StorageObjectLocator(StorageProviderKind.B2, "taskpilot-recordings", "recordings/10/5/rejected.webm"),
                "B2_FALLBACK_OVERSIZED",
                "Oversized object rejected",
                notBefore
        );

        ArgumentCaptor<StorageCleanupRequest> reqCap = ArgumentCaptor.forClass(StorageCleanupRequest.class);
        verify(cleanupPort).enqueueCleanup(reqCap.capture());
        assertThat(reqCap.getValue().notBefore()).isEqualTo(notBefore);
        assertThat(reserved.getStatus()).isEqualTo(StorageUploadOperationStatus.COMPENSATION_QUEUED);
    }
}
