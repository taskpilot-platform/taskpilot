package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.ProjectFileEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectFileRepository extends JpaRepository<ProjectFileEntity, Long> {

    Page<ProjectFileEntity> findByProjectId(Long projectId, Pageable pageable);

    Page<ProjectFileEntity> findByProjectIdAndOriginalNameContainingIgnoreCase(Long projectId, String keyword, Pageable pageable);

    Optional<ProjectFileEntity> findByIdAndProjectId(Long id, Long projectId);

    long countByProjectId(Long projectId);
}
