package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.TaskDeadlineRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskDeadlineRevisionRepository extends JpaRepository<TaskDeadlineRevisionEntity, Long> {

    List<TaskDeadlineRevisionEntity> findByTaskIdOrderByRevisionNoDesc(Long taskId);

    @Modifying
    @Meta(comment = "TaskDeadlineRevisionRepository.deleteByTaskId")
    @Query("DELETE FROM TaskDeadlineRevisionEntity r WHERE r.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);
}
