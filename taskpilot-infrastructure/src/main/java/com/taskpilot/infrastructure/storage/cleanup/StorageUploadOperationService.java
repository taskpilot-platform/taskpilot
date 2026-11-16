package com.taskpilot.infrastructure.storage.cleanup;

import com.taskpilot.contracts.storage.StorageCleanupPort;
import com.taskpilot.contracts.storage.StorageCleanupRequest;
import com.taskpilot.contracts.storage.StorageObjectLocator;
import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.contracts.storage.StorageUploadOperationDto;
import com.taskpilot.contracts.storage.StorageUploadOperationPort;
import com.taskpilot.contracts.storage.StorageUploadOperationStatus;
import com.taskpilot.contracts.storage.StorageUploadReservationCommand;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveFileDto;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Durable upload reservation, locator persistence, transactional attachment,
 * and post-crash/rollback compensation coordinator.
 */
@Slf4j
@Service
public class StorageUploadOperationService implements StorageUploadOperationPort {

    private static final List<StorageUploadOperationStatus> STALE_STATUSES = List.of(
            StorageUploadOperationStatus.RESERVED,
            StorageUploadOperationStatus.PROVIDER_WRITTEN
    );

    private final StorageUploadOperationRepository repository;
    private final StorageCleanupPort cleanupPort;
    private final GoogleDriveService googleDriveService;
    private final TransactionTemplate requiresNewTx;
    private final Duration defaultLeaseDuration;

    @Autowired
    public StorageUploadOperationService(
            StorageUploadOperationRepository repository,
            StorageCleanupPort cleanupPort,
            @Autowired(required = false) GoogleDriveService googleDriveService,
            @Autowired(required = false) PlatformTransactionManager transactionManager,
            @Value("${taskpilot.storage.upload.lease-seconds:600}") long leaseSeconds) {
        this.repository = repository;
        this.cleanupPort = cleanupPort;
        this.googleDriveService = googleDriveService;
        if (transactionManager != null) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = tx;
        } else {
            this.requiresNewTx = null;
        }
        this.defaultLeaseDuration = Duration.ofSeconds(Math.max(10L, leaseSeconds));
    }

    @Override
    public StorageUploadOperationDto reserve(StorageUploadReservationCommand command) {
        return executeRequiresNew(() -> {
            Instant now = Instant.now();
            StorageUploadOperationEntity entity = StorageUploadOperationEntity.builder()
                    .operationId(UUID.randomUUID())
                    .ownerUserId(command.ownerUserId())
                    .projectId(command.projectId())
                    .targetDomain(command.targetDomain())
                    .provider(command.provider())
                    .bucket(command.bucket())
                    .plannedObjectKey(command.plannedObjectKey())
                    .originalFilename(truncate(command.originalFilename(), 255))
                    .contentType(truncate(command.contentType(), 255))
                    .expectedSize(command.expectedSize())
                    .status(StorageUploadOperationStatus.RESERVED)
                    .leaseUntil(now.plus(defaultLeaseDuration))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            StorageUploadOperationEntity saved = repository.saveAndFlush(entity);
            return toDto(saved);
        });
    }

    @Override
    public StorageUploadOperationDto markProviderWritten(UUID operationId, String providerObjectKey) {
        return executeRequiresNew(() -> {
            StorageUploadOperationEntity entity = repository.findByIdForUpdate(operationId)
                    .orElseThrow(() -> new IllegalStateException("Upload operation not found: " + operationId));
            if (entity.getStatus()!=StorageUploadOperationStatus.RESERVED
                    && !(entity.getStatus()==StorageUploadOperationStatus.PROVIDER_WRITTEN
                    && java.util.Objects.equals(entity.getProviderObjectKey(),providerObjectKey))) {
                throw new IllegalStateException("Upload operation is no longer writable");
            }
            entity.setProviderObjectKey(providerObjectKey);
            entity.setLeaseUntil(Instant.now().plus(defaultLeaseDuration));
            entity.setStatus(StorageUploadOperationStatus.PROVIDER_WRITTEN);
            entity.setUpdatedAt(Instant.now());
            return toDto(repository.saveAndFlush(entity));
        });
    }

    @Override
    public void markAttachedInCurrentTransaction(UUID operationId, long attachedEntityId) {
        if (operationId == null) {
            return;
        }
        StorageUploadOperationEntity entity = repository.findByIdForUpdate(operationId)
                .orElseThrow(() -> new IllegalStateException("Upload operation not found: " + operationId));
        if (entity.getStatus()==StorageUploadOperationStatus.ATTACHED
                && java.util.Objects.equals(entity.getAttachedEntityId(),attachedEntityId)) return;
        if (entity.getStatus()!=StorageUploadOperationStatus.PROVIDER_WRITTEN) {
            throw new IllegalStateException("Upload has expired or compensation already owns its object");
        }
        entity.setStatus(StorageUploadOperationStatus.ATTACHED);
        entity.setAttachedEntityId(attachedEntityId);
        entity.setErrorCode(null);
        entity.setErrorMessage(null);
        entity.setUpdatedAt(Instant.now());
        repository.saveAndFlush(entity);
    }

    @Override
    public void registerRollbackCompensation(
            UUID operationId,
            StorageObjectLocator fallbackLocator,
            String errorCode,
            String errorMessage
    ) {
        if (operationId == null && fallbackLocator == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        compensateFailedUpload(operationId, fallbackLocator, errorCode, errorMessage);
                    }
                }
            });
        }
    }

    @Override
    public void compensateFailedUpload(
            UUID operationId,
            StorageObjectLocator locator,
            String errorCode,
            String errorMessage
    ) {
        executeRequiresNew(() -> {
            StorageUploadOperationEntity entity = operationId != null
                    ? repository.findByIdForUpdate(operationId).orElse(null)
                    : null;

            if (entity != null && entity.getStatus() == StorageUploadOperationStatus.ATTACHED) {
                // Commit succeeded; never compensate an attached business file.
                return null;
            }

            if (entity != null && "UPLOAD_LEASE_EXPIRED".equals(errorCode)
                    && (!STALE_STATUSES.contains(entity.getStatus()) || !entity.getLeaseUntil().isBefore(Instant.now()))) return null;
            StorageObjectLocator effectiveLocator = locator;
            if (effectiveLocator == null && entity != null) {
                effectiveLocator = resolveKnownLocator(entity);
            }

            if (effectiveLocator != null) {
                Long projectId = entity != null ? entity.getProjectId() : null;
                cleanupPort.enqueueCleanup(new StorageCleanupRequest(
                        effectiveLocator,
                        "UPLOAD_COMPENSATION",
                        operationId != null ? operationId.toString() : null,
                        projectId,
                        operationId
                ));
                if (entity != null) {
                    entity.setStatus(StorageUploadOperationStatus.COMPENSATION_QUEUED);
                    if (entity.getProviderObjectKey() == null) {
                        entity.setProviderObjectKey(effectiveLocator.objectKey());
                    }
                    entity.setErrorCode(truncate(errorCode, 64));
                    entity.setErrorMessage(StorageCleanupService.sanitizeErrorMessage(errorMessage));
                    entity.setUpdatedAt(Instant.now());
                    repository.saveAndFlush(entity);
                }
            } else if (entity != null && entity.getProvider() == StorageProviderKind.GOOGLE_DRIVE) {
                // Drive write failed/timed out before locator returned; mark lease expired so reconciliation checks appProperties.
                entity.setErrorCode(truncate(errorCode, 64));
                entity.setErrorMessage(StorageCleanupService.sanitizeErrorMessage(errorMessage));
                entity.setLeaseUntil(Instant.now());
                entity.setUpdatedAt(Instant.now());
                repository.saveAndFlush(entity);
            }
            return null;
        });
    }

    @Override
    public void markAborted(UUID operationId, String errorCode, String errorMessage) {
        if (operationId == null) {
            return;
        }
        executeRequiresNew(() -> {
            repository.findByIdForUpdate(operationId).ifPresent(entity -> {
                if (entity.getStatus() == StorageUploadOperationStatus.RESERVED) {
                    entity.setStatus(StorageUploadOperationStatus.ABORTED);
                    entity.setErrorCode(truncate(errorCode, 64));
                    entity.setErrorMessage(StorageCleanupService.sanitizeErrorMessage(errorMessage));
                    entity.setUpdatedAt(Instant.now());
                    repository.saveAndFlush(entity);
                }
            });
            return null;
        });
    }

    /**
     * Reconciles expired RESERVED/PROVIDER_WRITTEN operations while leaving active leased uploads untouched.
     */
    public int reconcileExpiredOperations(int batchSize) {
        int bounded = Math.min(Math.max(batchSize, 1), 100);
        List<StorageUploadOperationEntity> expired = executeRequiresNew(() ->
                repository.findExpiredOperations(STALE_STATUSES, Instant.now(), PageRequest.of(0, bounded))
        );
        if (expired == null || expired.isEmpty()) {
            return 0;
        }

        int reconciled = 0;
        for (StorageUploadOperationEntity op : expired) {
            reconcileSingleOperation(op);
            reconciled++;
        }
        return reconciled;
    }

    private void reconcileSingleOperation(StorageUploadOperationEntity op) {
        StorageObjectLocator knownLocator = resolveKnownLocator(op);
        if (knownLocator != null) {
            compensateFailedUpload(
                    op.getOperationId(),
                    knownLocator,
                    "UPLOAD_LEASE_EXPIRED",
                    "Expired unattached upload operation reconciled"
            );
            return;
        }

        if (op.getProvider() != StorageProviderKind.GOOGLE_DRIVE) {
            markAborted(op.getOperationId(), "UPLOAD_NO_PLANNED_KEY", "Expired upload had no planned key");
            return;
        }

        if (googleDriveService == null) {
            deferDriveCorrelation(op.getOperationId(), "DRIVE_UNAVAILABLE", "GoogleDriveService unavailable for correlation");
            return;
        }

        // Execute Drive appProperties correlation lookup OUTSIDE DB transaction.
        List<GoogleDriveFileDto> matchedFiles;
        try {
            matchedFiles = googleDriveService.findFilesByOperationId(op.getOperationId());
        } catch (Exception ex) {
            deferDriveCorrelation(op.getOperationId(), "DRIVE_CORRELATION_RETRY", ex.getMessage());
            return;
        }

        if (matchedFiles == null || matchedFiles.isEmpty()) {
            markAborted(op.getOperationId(), "DRIVE_NO_OBJECT_FOUND", "Correlation found no Drive file for expired reservation");
            return;
        }

        executeRequiresNew(() -> {
            StorageUploadOperationEntity current = repository.findByIdForUpdate(op.getOperationId()).orElse(null);
            if (current == null || !STALE_STATUSES.contains(current.getStatus())
                    || !current.getLeaseUntil().isBefore(Instant.now())) {
                return null;
            }
            List<StorageCleanupRequest> requests = new ArrayList<>();
            String firstId = null;
            for (GoogleDriveFileDto dto : matchedFiles) {
                if (dto.getFileId() == null || dto.getFileId().isBlank()) {
                    continue;
                }
                if (firstId == null) {
                    firstId = dto.getFileId();
                }
                requests.add(new StorageCleanupRequest(
                        new StorageObjectLocator(StorageProviderKind.GOOGLE_DRIVE, current.getBucket(), dto.getFileId()),
                        "DRIVE_UNCORRELATED_RECONCILE",
                        current.getOperationId().toString(),
                        current.getProjectId(),
                        current.getOperationId()
                ));
            }
            if (!requests.isEmpty()) {
                cleanupPort.enqueueCleanupBatch(requests);
                current.setProviderObjectKey(firstId);
                current.setStatus(StorageUploadOperationStatus.COMPENSATION_QUEUED);
                current.setErrorCode("DRIVE_CRASH_WINDOW_RECONCILED");
                current.setErrorMessage("Correlated unattached Drive upload via appProperties");
                current.setUpdatedAt(Instant.now());
                repository.saveAndFlush(current);
            }
            return null;
        });
    }

    private void deferDriveCorrelation(UUID operationId, String errorCode, String errorMessage) {
        executeRequiresNew(() -> {
            repository.findByIdForUpdate(operationId).ifPresent(current -> {
                if (current.getStatus() == StorageUploadOperationStatus.RESERVED) {
                    current.setErrorCode(truncate(errorCode, 64));
                    current.setErrorMessage(StorageCleanupService.sanitizeErrorMessage(errorMessage));
                    current.setLeaseUntil(Instant.now().plusSeconds(60));
                    current.setUpdatedAt(Instant.now());
                    repository.saveAndFlush(current);
                }
            });
            return null;
        });
    }

    private static StorageObjectLocator resolveKnownLocator(StorageUploadOperationEntity entity) {
        if (entity.getProviderObjectKey() != null && !entity.getProviderObjectKey().isBlank()) {
            return new StorageObjectLocator(entity.getProvider(), entity.getBucket(), entity.getProviderObjectKey());
        }
        if (entity.getProvider() == StorageProviderKind.S3
                && entity.getPlannedObjectKey() != null
                && !entity.getPlannedObjectKey().isBlank()) {
            return new StorageObjectLocator(StorageProviderKind.S3, entity.getBucket(), entity.getPlannedObjectKey());
        }
        return null;
    }

    private <T> T executeRequiresNew(java.util.function.Supplier<T> action) {
        if (requiresNewTx == null) {
            return action.get();
        }
        return requiresNewTx.execute(status -> action.get());
    }

    private static StorageUploadOperationDto toDto(StorageUploadOperationEntity entity) {
        return new StorageUploadOperationDto(
                entity.getOperationId(),
                entity.getOwnerUserId(),
                entity.getProjectId(),
                entity.getTargetDomain(),
                entity.getProvider(),
                entity.getBucket(),
                entity.getPlannedObjectKey(),
                entity.getProviderObjectKey(),
                entity.getStatus(),
                entity.getAttachedEntityId(),
                entity.getLeaseUntil()
        );
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}
