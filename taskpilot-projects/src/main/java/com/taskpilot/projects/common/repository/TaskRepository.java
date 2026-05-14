package com.taskpilot.projects.common.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.TaskEntity;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, Long> {
    long countByProjectId(Long projectId);
    long countByProjectIdAndStatus(Long projectId, TaskEntity.TaskStatus status);
    
    List<TaskEntity> findByProjectId(Long projectId);
    List<TaskEntity> findByAssigneeId(Long assigneeId);
    List<TaskEntity> findByParentId(Long parentId);
    List<TaskEntity> findBySprintId(Long sprintId);
    List<TaskEntity> findByProjectIdAndSprintIdIsNullOrderByPositionAsc(Long projectId);

    long countBySprintId(Long sprintId);

    boolean existsBySprintIdAndStatusNot(Long sprintId, TaskEntity.TaskStatus status);

    @Modifying
    @Query("UPDATE TaskEntity t SET t.sprintId = NULL WHERE t.sprintId = :sprintId")
    int clearSprintId(@Param("sprintId") Long sprintId);
}
