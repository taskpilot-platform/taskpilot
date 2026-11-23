package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.SprintClosureCorrectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SprintClosureCorrectionRepository extends JpaRepository<SprintClosureCorrectionEntity, Long> {

    List<SprintClosureCorrectionEntity> findByProjectIdAndSprintIdOrderByOccurredAtDesc(Long projectId, Long sprintId);
}
