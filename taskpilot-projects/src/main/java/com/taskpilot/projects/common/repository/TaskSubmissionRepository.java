package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.TaskSubmissionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskSubmissionRepository extends JpaRepository<TaskSubmissionEntity, Long> {

    List<TaskSubmissionEntity> findByTaskIdOrderByCycleNoDesc(Long taskId);

    List<TaskSubmissionEntity> findByIdIn(Collection<Long> ids);

    boolean existsByTaskId(Long taskId);

    @Meta(comment = "TaskSubmissionRepository.findMaxCycleNoByTaskId")
    @Query("SELECT COALESCE(MAX(s.cycleNo), 0) FROM TaskSubmissionEntity s WHERE s.taskId = :taskId")
    int findMaxCycleNoByTaskId(@Param("taskId") Long taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Meta(comment = "TaskSubmissionRepository.findByIdForUpdate")
    @Query("SELECT s FROM TaskSubmissionEntity s WHERE s.id = :id")
    Optional<TaskSubmissionEntity> findByIdForUpdate(@Param("id") Long id);
}
