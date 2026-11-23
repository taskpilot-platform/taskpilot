package com.taskpilot.ai.service;

import com.taskpilot.contracts.project.port.out.ProjectAiPurgePort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI-module purge participant for whole-project permanent deletion.
 * Deletes project-scoped adaptive outcomes/invalidations, recommendation outcomes,
 * decision events, snapshot candidates, snapshots, RAG documents, and AI logs in FK-safe order.
 */
@Component
@RequiredArgsConstructor
public class ProjectAiPurgeAdapter implements ProjectAiPurgePort {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Integer> purgeProjectAiData(long projectId) {
        Map<String, Integer> counts = new LinkedHashMap<>();

        counts.put("adaptive_outcome_invalidations", executeDelete("""
                DELETE FROM public.adaptive_outcome_invalidations
                WHERE project_id = :projectId
                   OR task_id IN (SELECT id FROM public.tasks WHERE project_id = :projectId)
                """, projectId));

        counts.put("adaptive_reviewed_outcomes", executeDelete("""
                DELETE FROM public.adaptive_reviewed_outcomes
                WHERE project_id = :projectId
                   OR task_id IN (SELECT id FROM public.tasks WHERE project_id = :projectId)
                   OR decision_id IN (
                        SELECT decision_id FROM public.recommendation_decision_events WHERE project_id = :projectId
                   )
                   OR snapshot_id IN (
                        SELECT snapshot_id FROM public.recommendation_snapshots WHERE project_id = :projectId
                   )
                """, projectId));

        counts.put("recommendation_task_outcomes", executeDelete("""
                DELETE FROM public.recommendation_task_outcomes
                WHERE project_id = :projectId
                   OR task_id IN (SELECT id FROM public.tasks WHERE project_id = :projectId)
                   OR decision_id IN (
                        SELECT decision_id FROM public.recommendation_decision_events WHERE project_id = :projectId
                   )
                   OR snapshot_id IN (
                        SELECT snapshot_id FROM public.recommendation_snapshots WHERE project_id = :projectId
                   )
                """, projectId));

        counts.put("recommendation_decision_events", executeDelete("""
                DELETE FROM public.recommendation_decision_events
                WHERE project_id = :projectId
                   OR snapshot_id IN (
                        SELECT snapshot_id FROM public.recommendation_snapshots WHERE project_id = :projectId
                   )
                """, projectId));

        counts.put("recommendation_snapshot_candidates", executeDelete("""
                DELETE FROM public.recommendation_snapshot_candidates
                WHERE snapshot_id IN (
                    SELECT snapshot_id FROM public.recommendation_snapshots WHERE project_id = :projectId
                )
                """, projectId));

        counts.put("recommendation_snapshots", executeDelete("""
                DELETE FROM public.recommendation_snapshots
                WHERE project_id = :projectId
                """, projectId));

        counts.put("document_chunk_staging", executeDelete("""
                DELETE FROM public.document_chunk_staging
                WHERE document_id IN (SELECT id FROM public.documents WHERE project_id = :projectId)
                """, projectId));

        counts.put("document_chunks", executeDelete("""
                DELETE FROM public.document_chunks
                WHERE project_id = :projectId
                   OR document_id IN (SELECT id FROM public.documents WHERE project_id = :projectId)
                """, projectId));

        counts.put("documents", executeDelete("""
                DELETE FROM public.documents
                WHERE project_id = :projectId
                """, projectId));

        counts.put("ai_logs", executeDelete("""
                DELETE FROM public.ai_logs
                WHERE project_id = :projectId
                """, projectId));

        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasTaskRecommendationOutcomes(long taskId) {
        Object result = entityManager.createNativeQuery("""
                SELECT EXISTS (
                    SELECT 1 FROM public.recommendation_task_outcomes WHERE task_id = :taskId
                    UNION ALL
                    SELECT 1 FROM public.adaptive_reviewed_outcomes WHERE task_id = :taskId
                )
                """)
                .setParameter("taskId", taskId)
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }

    private int executeDelete(String sql, long projectId) {
        return entityManager.createNativeQuery(sql)
                .setParameter("projectId", projectId)
                .executeUpdate();
    }
}
