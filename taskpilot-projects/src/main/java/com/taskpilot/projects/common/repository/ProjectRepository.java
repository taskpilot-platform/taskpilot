package com.taskpilot.projects.common.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.taskpilot.projects.common.entity.ProjectEntity;

public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {
    boolean existsByName(String name);

    Optional<ProjectEntity> findByName(String name);

    @Meta(comment = "ProjectRepository.findByIdForUpdate")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProjectEntity p WHERE p.id = :id")
    Optional<ProjectEntity> findByIdForUpdate(@Param("id") Long id);

    List<ProjectEntity> findByNeedsManagerAttentionTrueOrderByManagementAttentionSinceAsc();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ProjectEntity p WHERE p.id = :projectId")
    void deleteProjectById(@Param("projectId") Long projectId);
}
