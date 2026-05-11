package com.taskpilot.projects.common.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.TaskRequiredSkillEntity;

@Repository
public interface TaskRequiredSkillRepository extends JpaRepository<TaskRequiredSkillEntity, TaskRequiredSkillEntity.TaskRequiredSkillId> {
    List<TaskRequiredSkillEntity> findByTaskId(Long taskId);
    void deleteByTaskId(Long taskId);

    @Query("SELECT t FROM TaskRequiredSkillEntity t WHERE t.taskId IN :taskIds")
    List<TaskRequiredSkillEntity> findByTaskIdIn(@Param("taskIds") Collection<Long> taskIds);
}
