package com.taskpilot.projects.common.repository;

import java.util.List;
import com.taskpilot.projects.common.entity.TaskEntity;
import org.springframework.data.domain.Sort;

/**
 * Modern Spring Data Fragment interface for specialized task queries.
 * Demonstrates fragment composition pattern, cleanly separating complex
 * dynamic queries from the standard CRUD repository.
 */
public interface TaskSearchFragment {

    List<TaskEntity> searchTasksCustom(Long projectId, String keyword, Sort sort);
}
