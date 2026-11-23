package com.taskpilot.contracts.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * Contracts port for durable upload operation reservation, locator persistence,
 * transactional attachment, and rollback/crash compensation.
 */
public interface StorageUploadOperationPort {

    StorageUploadOperationDto reserve(StorageUploadReservationCommand command);

    StorageUploadOperationDto markProviderWritten(UUID operationId, String providerObjectKey);

    void markAttachedInCurrentTransaction(UUID operationId, long attachedEntityId);

    void registerRollbackCompensation(
            UUID operationId,
            StorageObjectLocator fallbackLocator,
            String errorCode,
            String errorMessage
    );

    void compensateFailedUpload(
            UUID operationId,
            StorageObjectLocator locator,
            String errorCode,
            String errorMessage
    );

    default void compensateFailedUpload(
            UUID operationId,
            StorageObjectLocator locator,
            String errorCode,
            String errorMessage,
            Instant cleanupNotBefore
    ) {
        compensateFailedUpload(operationId, locator, errorCode, errorMessage);
    }

    void markAborted(UUID operationId, String errorCode, String errorMessage);
}
