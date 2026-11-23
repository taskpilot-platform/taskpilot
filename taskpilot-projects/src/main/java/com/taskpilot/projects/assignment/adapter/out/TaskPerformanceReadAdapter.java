package com.taskpilot.projects.assignment.adapter.out;

import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics.DataStatus;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics.Performance;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics.Workload;
import com.taskpilot.contracts.assignment.port.out.TaskPerformanceReadPort;
import com.taskpilot.contracts.task.TaskQualityRubric;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TaskPerformanceReadAdapter implements TaskPerformanceReadPort {
    private static final int WINDOW_LIMIT = 20;
    private static final double PRIOR_STRENGTH = 5.0;
    private static final double PRIOR_MEAN = 0.5;

    // One statement gives both aggregates the same PostgreSQL MVCC snapshot.
    private static final String READ_METRICS = """
            WITH candidates AS (
                SELECT pm.user_id
                FROM public.project_members pm
                JOIN public.projects p ON p.id = pm.project_id
                JOIN public.users u ON u.id = pm.user_id
                WHERE pm.project_id = :projectId AND pm.user_id IN (:candidateIds)
                  AND p.status <> 'ARCHIVED' AND u.account_state = 'ACTIVE' AND u.email_verified
            ), eligible AS (
                SELECT e.assignee_id, e.id, e.evidence_revision, e.quality_rating, e.commitment_due_at, e.on_time,
                       row_number() OVER (PARTITION BY e.assignee_id
                           ORDER BY e.accepted_submitted_at DESC, t.id DESC) AS slot
                FROM public.tasks t
                JOIN public.task_evaluations e ON e.id = t.current_evaluation_id
                                              AND e.task_id = t.id AND e.project_id = t.project_id
                JOIN candidates c ON c.user_id = e.assignee_id
                WHERE t.project_id = :projectId AND t.deleted_at IS NULL AND t.status = 'DONE'
                  AND NOT e.invalidated AND e.evidence_status = 'INDEPENDENT_ACCEPTED'
                  AND e.rubric_version = :rubricVersion AND e.quality_rating BETWEEN 1 AND 5
            ), quality AS (
                SELECT assignee_id, count(*) AS n, sum((quality_rating - 1) / 4.0) AS quality_sum,
                       count(commitment_due_at) AS dated,
                       count(*) FILTER (WHERE commitment_due_at IS NOT NULL AND on_time IS TRUE) AS timely,
                       md5(string_agg(id || ':' || evidence_revision, ',' ORDER BY slot)) AS revision
                FROM eligible WHERE slot <= :limit GROUP BY assignee_id
            ), workload AS (
                SELECT t.assignee_id, count(*) AS active_count,
                       md5(string_agg(t.id || ':' || t.version, ',' ORDER BY t.id)) AS revision
                FROM public.tasks t JOIN candidates c ON c.user_id = t.assignee_id
                WHERE t.project_id = :projectId AND t.deleted_at IS NULL
                  AND t.status IN ('TODO', 'IN_PROGRESS', 'REVIEW')
                GROUP BY t.assignee_id
            )
            SELECT c.user_id, q.n, q.quality_sum, q.dated, q.timely, q.revision AS evidence_revision,
                   w.active_count, w.revision AS workload_revision
            FROM candidates c
            LEFT JOIN quality q ON q.assignee_id = c.user_id
            LEFT JOIN workload w ON w.assignee_id = c.user_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public TaskPerformanceReadAdapter(NamedParameterJdbcTemplate jdbc, java.util.Optional<Clock> clock) {
        this.jdbc = jdbc;
        this.clock = clock.orElseGet(Clock::systemUTC);
    }

    @Override
    public Map<Long, TaskAssignmentMetrics> readProjectMetrics(long projectId, Set<Long> candidateUserIds) {
        if (candidateUserIds.isEmpty()) {
            return Map.of();
        }
        Instant asOf = clock.instant();
        return jdbc.query(READ_METRICS, Map.of("projectId", projectId, "candidateIds", candidateUserIds,
                "limit", WINDOW_LIMIT, "rubricVersion", TaskQualityRubric.RUBRIC_VERSION), rs -> {
            Map<Long, TaskAssignmentMetrics> result = new HashMap<>();
            while (rs.next()) {
                long userId = rs.getLong("user_id");
                int count = rs.getInt("n");
                int dated = rs.getInt("dated");
                double sum = rs.getDouble("quality_sum");
                Performance performance = new Performance("performance-v1", TaskQualityRubric.RUBRIC_VERSION,
                        count == 0 ? DataStatus.DEFAULT : DataStatus.MEASURED,
                        (sum + PRIOR_STRENGTH * PRIOR_MEAN) / (count + PRIOR_STRENGTH),
                        count == 0 ? null : sum / count, count, WINDOW_LIMIT, count / (count + PRIOR_STRENGTH),
                        dated, dated == 0 ? null : (double) rs.getInt("timely") / dated, count - dated,
                        revision(rs.getString("evidence_revision")));
                Workload workload = new Workload(rs.getLong("active_count"), "ACTIVE_TASK_COUNT", "PROJECT",
                        revision(rs.getString("workload_revision")));
                result.put(userId, new TaskAssignmentMetrics(projectId, userId, asOf, performance, workload));
            }
            return Map.copyOf(result);
        });
    }

    private static String revision(String digest) {
        return digest == null ? "empty" : digest;
    }
}
