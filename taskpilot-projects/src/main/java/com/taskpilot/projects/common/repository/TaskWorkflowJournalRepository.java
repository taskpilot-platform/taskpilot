package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.TaskWorkflowJournalEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskWorkflowJournalRepository extends JpaRepository<TaskWorkflowJournalEntity, Long> {

    List<TaskWorkflowJournalEntity> findByTaskIdOrderByIdAsc(Long taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Meta(comment = "TaskWorkflowJournalRepository.findByIdForUpdate")
    @Query("SELECT j FROM TaskWorkflowJournalEntity j WHERE j.id = :id")
    Optional<TaskWorkflowJournalEntity> findByIdForUpdate(@Param("id") Long id);

    @Meta(comment = "TaskWorkflowJournalRepository.findDueJournalIds")
    @Query("SELECT j.id FROM TaskWorkflowJournalEntity j WHERE j.status IN ('PENDING', 'RETRY_PENDING') AND j.nextAttemptAt <= :now ORDER BY j.id ASC")
    List<Long> findDueJournalIds(@Param("now") Instant now);

    @Meta(comment = "TaskWorkflowJournalRepository.findPendingIdsByTaskId")
    @Query("SELECT j.id FROM TaskWorkflowJournalEntity j WHERE j.taskId = :taskId AND j.status IN ('PENDING', 'RETRY_PENDING') ORDER BY j.id ASC")
    List<Long> findPendingIdsByTaskId(@Param("taskId") Long taskId);
}
