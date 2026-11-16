package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.UserProjectReconciliationStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProjectReconciliationStateRepository extends JpaRepository<UserProjectReconciliationStateEntity, Long> {
}
