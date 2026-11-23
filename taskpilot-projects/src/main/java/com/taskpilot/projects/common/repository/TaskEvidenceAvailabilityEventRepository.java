package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.TaskEvidenceAvailabilityEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TaskEvidenceAvailabilityEventRepository extends JpaRepository<TaskEvidenceAvailabilityEventEntity, Long> {

    List<TaskEvidenceAvailabilityEventEntity> findByEvidenceRefIdInOrderByOccurredAtDescIdDesc(Collection<Long> evidenceRefIds);
}
