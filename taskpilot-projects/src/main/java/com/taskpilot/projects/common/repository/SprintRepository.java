package com.taskpilot.projects.common.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.SprintEntity.SprintStatus;

@Repository
public interface SprintRepository extends JpaRepository<SprintEntity, Long> {
    List<SprintEntity> findByProjectIdOrderByStartDateAsc(Long projectId);

    List<SprintEntity> findByProjectIdOrderByStartDateAscIdAsc(Long projectId);

    Optional<SprintEntity> findByProjectIdAndStatus(Long projectId, SprintStatus status);

    boolean existsByProjectIdAndStatus(Long projectId, SprintStatus status);
}
