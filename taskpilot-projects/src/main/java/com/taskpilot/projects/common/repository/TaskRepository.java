package com.taskpilot.projects.common.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.TaskEntity;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, Long> {
    long countByProjectId(Long projectId);
    long countByProjectIdAndStatus(Long projectId, TaskEntity.TaskStatus status);
    
    List<TaskEntity> findByProjectId(Long projectId);
    List<TaskEntity> findByParentId(Long parentId);
}
