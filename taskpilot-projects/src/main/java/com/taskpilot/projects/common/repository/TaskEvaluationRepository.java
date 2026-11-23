package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.TaskEvaluationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskEvaluationRepository extends JpaRepository<TaskEvaluationEntity, Long> {

    List<TaskEvaluationEntity> findByTaskIdOrderByEvaluationVersionDescIdDesc(Long taskId);

    List<TaskEvaluationEntity> findByIdIn(Collection<Long> ids);

    boolean existsByTaskId(Long taskId);

    @Meta(comment = "TaskEvaluationRepository.findMaxVersionBySubmissionId")
    @Query("SELECT COALESCE(MAX(e.evaluationVersion), 0) FROM TaskEvaluationEntity e WHERE e.submissionId = :submissionId")
    int findMaxVersionBySubmissionId(@Param("submissionId") Long submissionId);

    @Meta(comment = "TaskEvaluationRepository.findMaxEvidenceRevisionByTaskId")
    @Query("SELECT COALESCE(MAX(e.evidenceRevision), 0) FROM TaskEvaluationEntity e WHERE e.taskId = :taskId")
    long findMaxEvidenceRevisionByTaskId(@Param("taskId") Long taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Meta(comment = "TaskEvaluationRepository.findByIdForUpdate")
    @Query("SELECT e FROM TaskEvaluationEntity e WHERE e.id = :id")
    Optional<TaskEvaluationEntity> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Meta(comment = "TaskEvaluationRepository.findActiveByTaskIdForUpdate")
    @Query("SELECT e FROM TaskEvaluationEntity e WHERE e.taskId = :taskId AND e.invalidated = false ORDER BY e.id ASC")
    List<TaskEvaluationEntity> findActiveByTaskIdForUpdate(@Param("taskId") Long taskId);
}
