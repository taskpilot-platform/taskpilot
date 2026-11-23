package com.taskpilot.infrastructure.storage.cleanup;

import com.taskpilot.contracts.storage.StorageProviderKind;
import com.taskpilot.contracts.storage.StorageTargetDomain;
import com.taskpilot.contracts.storage.StorageUploadOperationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "storage_upload_operations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageUploadOperationEntity {

    @Id
    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_domain", nullable = false, length = 32)
    private StorageTargetDomain targetDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private StorageProviderKind provider;

    @Column(name = "bucket", nullable = false, length = 255)
    private String bucket;

    @Column(name = "planned_object_key", length = 512)
    private String plannedObjectKey;

    @Column(name = "provider_object_key", length = 512)
    private String providerObjectKey;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "expected_size", nullable = false)
    private Long expectedSize;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private StorageUploadOperationStatus status = StorageUploadOperationStatus.RESERVED;

    @Column(name = "attached_entity_id")
    private Long attachedEntityId;

    @Column(name = "lease_until", nullable = false)
    private Instant leaseUntil;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
