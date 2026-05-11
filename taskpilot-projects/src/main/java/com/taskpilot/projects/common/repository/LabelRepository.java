package com.taskpilot.projects.common.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.LabelEntity;

@Repository
public interface LabelRepository extends JpaRepository<LabelEntity, Long> {
    List<LabelEntity> findByProjectId(Long projectId);
    boolean existsByProjectIdAndNameIgnoreCase(Long projectId, String name);
}
