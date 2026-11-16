package com.taskpilot.infrastructure.storage.cleanup;

import com.taskpilot.contracts.storage.StorageCleanupStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StorageCleanupJobRepository extends JpaRepository<StorageCleanupJobEntity, Long> {

    List<StorageCleanupJobEntity> findByProjectIdOrderByIdAsc(Long projectId);

    List<StorageCleanupJobEntity> findByStatusOrderByIdAsc(StorageCleanupStatus status);
}
