package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.ProjectDeletionAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectDeletionAuditRepository extends JpaRepository<ProjectDeletionAuditEntity, Long> {

    Optional<ProjectDeletionAuditEntity> findByProjectId(Long projectId);

    Optional<ProjectDeletionAuditEntity> findByDeletedByUserIdAndRequestId(Long deletedByUserId, UUID requestId);

    boolean existsByProjectId(Long projectId);
}
