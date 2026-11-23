package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.SprintClosureSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SprintClosureSnapshotRepository extends JpaRepository<SprintClosureSnapshotEntity, Long> {

    Optional<SprintClosureSnapshotEntity> findBySprintId(Long sprintId);

    List<SprintClosureSnapshotEntity> findByProjectIdOrderByClosedAtDesc(Long projectId);

    List<SprintClosureSnapshotEntity> findBySprintIdIn(List<Long> sprintIds);
}
