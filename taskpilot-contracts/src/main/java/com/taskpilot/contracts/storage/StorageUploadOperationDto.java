package com.taskpilot.contracts.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a durable upload operation.
 */
public record StorageUploadOperationDto(
        UUID operationId,
        long ownerUserId,
        long projectId,
        StorageTargetDomain targetDomain,
        StorageProviderKind provider,
        String bucket,
        String plannedObjectKey,
        String providerObjectKey,
        StorageUploadOperationStatus status,
        Long attachedEntityId,
        Instant leaseUntil
) {
}
