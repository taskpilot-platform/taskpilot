package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.ProjectManagementAttentionAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectManagementAttentionAuditRepository extends JpaRepository<ProjectManagementAttentionAuditEntity, UUID> {

    List<ProjectManagementAttentionAuditEntity> findByProjectIdOrderByOccurredAtDesc(Long projectId);
}
