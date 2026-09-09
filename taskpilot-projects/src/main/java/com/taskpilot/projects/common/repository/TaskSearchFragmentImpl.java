package com.taskpilot.projects.common.repository;

import java.util.List;
import com.taskpilot.projects.common.entity.TaskEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * Implementation of TaskSearchFragment composited automatically into TaskRepository
 * by Spring Data JPA fragment composition.
 */
@Repository
public class TaskSearchFragmentImpl implements TaskSearchFragment {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<TaskEntity> searchTasksCustom(Long projectId, String keyword, Sort sort) {
        StringBuilder jpql = new StringBuilder("SELECT t FROM TaskEntity t WHERE t.projectId = :projectId");
        if (keyword != null && !keyword.isBlank()) {
            jpql.append(" AND (LOWER(t.title) LIKE LOWER(:kw) OR LOWER(t.description) LIKE LOWER(:kw))");
        }
        if (sort != null && sort.isSorted()) {
            jpql.append(" ORDER BY ");
            String orders = sort.stream()
                    .map(order -> "t." + order.getProperty() + " " + order.getDirection().name())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("t.id ASC");
            jpql.append(orders);
        } else {
            jpql.append(" ORDER BY t.id ASC");
        }

        TypedQuery<TaskEntity> query = entityManager.createQuery(jpql.toString(), TaskEntity.class);
        query.setParameter("projectId", projectId);
        if (keyword != null && !keyword.isBlank()) {
            query.setParameter("kw", "%" + keyword.trim() + "%");
        }
        return query.getResultList();
    }
}
