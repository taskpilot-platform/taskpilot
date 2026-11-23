package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.TaskEvaluationInvalidationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskEvaluationInvalidationRepository extends JpaRepository<TaskEvaluationInvalidationEntity, Long> {

    List<TaskEvaluationInvalidationEntity> findByTaskIdOrderByOccurredAtDescIdDesc(Long taskId);

    Optional<TaskEvaluationInvalidationEntity> findByEvaluationId(Long evaluationId);

    List<TaskEvaluationInvalidationEntity> findByEvaluationIdIn(Collection<Long> evaluationIds);
}
