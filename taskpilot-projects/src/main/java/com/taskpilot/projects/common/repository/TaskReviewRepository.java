package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.TaskReviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskReviewRepository extends JpaRepository<TaskReviewEntity, Long> {

    List<TaskReviewEntity> findByTaskIdOrderByReviewedAtDescIdDesc(Long taskId);

    boolean existsByTaskId(Long taskId);
}
