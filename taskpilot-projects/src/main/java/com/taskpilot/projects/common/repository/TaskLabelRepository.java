package com.taskpilot.projects.common.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.TaskLabelEntity;

@Repository
public interface TaskLabelRepository extends JpaRepository<TaskLabelEntity, TaskLabelEntity.TaskLabelId> {
    List<TaskLabelEntity> findByTaskId(Long taskId);
    void deleteByTaskId(Long taskId);

    @Query("SELECT t FROM TaskLabelEntity t WHERE t.taskId IN :taskIds")
    List<TaskLabelEntity> findByTaskIdIn(@Param("taskIds") Collection<Long> taskIds);
}
