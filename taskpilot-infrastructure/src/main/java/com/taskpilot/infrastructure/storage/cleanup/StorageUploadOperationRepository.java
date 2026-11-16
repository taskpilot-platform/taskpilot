package com.taskpilot.infrastructure.storage.cleanup;

import com.taskpilot.contracts.storage.StorageUploadOperationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface StorageUploadOperationRepository extends JpaRepository<StorageUploadOperationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select op from StorageUploadOperationEntity op where op.operationId=:id")
    Optional<StorageUploadOperationEntity> findByIdForUpdate(@Param("id") UUID id);


    @Query("""
            SELECT op FROM StorageUploadOperationEntity op
            WHERE op.status IN :statuses
              AND op.leaseUntil < :now
            ORDER BY op.leaseUntil ASC, op.createdAt ASC
            """)
    List<StorageUploadOperationEntity> findExpiredOperations(
            @Param("statuses") List<StorageUploadOperationStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );
}
