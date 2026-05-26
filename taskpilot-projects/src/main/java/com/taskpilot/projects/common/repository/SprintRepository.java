package com.taskpilot.projects.common.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.SprintEntity;

@Repository
public interface SprintRepository extends JpaRepository<SprintEntity, Long> {
    List<SprintEntity> findByProjectIdOrderByStartDateAscIdAsc(Long projectId);
}
