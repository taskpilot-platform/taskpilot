package com.taskpilot.projects.aiquery;

import com.taskpilot.contracts.aiquery.dto.AiResourceRef;
import com.taskpilot.contracts.aiquery.port.out.AiResourceAccessPort;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Projects owns resource relationships and row locks. Only fixed table names can enter SQL. */
@Component
@RequiredArgsConstructor
public class ProjectAiResourceAccessAdapter implements AiResourceAccessPort {
    private final EntityManager em;
    private final AccountAuthStatePort accounts;
    private static final Map<String,String> TABLES = Map.of(
            "PROJECT","projects", "TASK","tasks", "SPRINT","sprints",
            "COMMENT","comments", "FILE","project_files", "MEETING","project_meetings", "LABEL","labels");
    private static final Map<String,String> COLLECTIONS = Map.of(
            "TASKS","tasks", "SPRINTS","sprints", "MEMBERS","project_members", "FILES","project_files",
            "MEETINGS","project_meetings", "LABELS","labels");

    @Override @Transactional
    public String fingerprint(long userId, List<AiResourceRef> resources, List<Long> targetUsers, boolean lock) {
        List<AiResourceRef> refs = resources.stream().distinct().sorted().toList();
        SortedSet<Long> projectIds = new TreeSet<>();
        for (AiResourceRef r : refs) {
            if (r.kind().equals("OWN_PROJECTS")) continue;
            projectIds.add(projectId(r));
        }
        if (projectIds.size() > 1) throw new BusinessException(404, "Resources do not belong to the same project");
        if (lock) {
            for (Long id : projectIds) rows("select id from public.projects where id=:id for update",id);
        }
        SortedSet<Long> users = new TreeSet<>(targetUsers);
        users.add(userId);
        var states = (lock ? accounts.lockAndLoadByIds(users) : accounts.findByIds(users)).values();
        StringBuilder revision = new StringBuilder();
        // A pessimistic lookup may return an already-managed UserEntity. Read the security
        // projection natively after locks so a revoke committed while we waited cannot be stale.
        for (Long uid : users) {
            List<?> fresh=rows("select account_state::text, email_verified, token_version, role::text "
                    + "from public.users where id=:id",uid);
            if(fresh.isEmpty()) throw new BusinessException(404,"Account not found");
            Object[] state=(Object[])fresh.getFirst();
            if(!"ACTIVE".equals(state[0]) || !Boolean.TRUE.equals(state[1]))
                throw new BusinessException(403,"Account is not eligible for this action");
            revision.append(uid).append(Arrays.toString(state)).append(';');
        }
        if (states.size() != users.size()) throw new BusinessException(404,"Account not found");
        for (Long id : projectIds) {
            if (rows("select user_id from public.project_members where project_id=:id and user_id="+userId,id).isEmpty()) {
                throw new BusinessException(403,"You are not a member of this project");
            }
            revision.append(rows("select to_jsonb(r)::text from public.projects r where id=:id",id));
            revision.append(rows("select to_jsonb(r)::text from public.project_members r where project_id=:id order by user_id",id));
            for (Long target : targetUsers) {
                if (rows("select user_id from public.project_members where project_id=:id and user_id="+target,id).isEmpty()) {
                    throw new BusinessException(409,"Assignee is no longer a project member");
                }
            }
        }
        for (AiResourceRef r : refs) {
            if (r.kind().equals("OWN_PROJECTS")) {
                revision.append(rows("select to_jsonb(p)::text || to_jsonb(m)::text from public.projects p "
                        + "join public.project_members m on m.project_id=p.id where m.user_id=:id order by p.id",userId));
            } else if (COLLECTIONS.containsKey(r.kind())) {
                revision.append(rows("select to_jsonb(r)::text from public."+COLLECTIONS.get(r.kind())
                        + " r where project_id=:id order by " + (r.kind().equals("MEMBERS")?"user_id":"id"),r.id()));
            } else {
                String table = table(r.kind());
                if (lock) rows("select id from public."+table+" where id=:id for update",r.id());
                List<?> row = rows("select to_jsonb(r)::text from public."+table+" r where id=:id",r.id());
                if (row.isEmpty()) throw new BusinessException(404,"Resource no longer exists");
                // Resolve the relationship again after acquiring locks, not from a stale entity cache.
                if (!projectIds.contains(projectId(r))) throw new BusinessException(409,"Resource scope changed");
                revision.append(r).append(row);
                if (r.kind().equals("TASK")) {
                    revision.append(rows("select to_jsonb(r)::text from public.task_required_skills r where task_id=:id order by skill_id",r.id()));
                    revision.append(rows("select to_jsonb(r)::text from public.task_labels r where task_id=:id order by label_id",r.id()));
                }
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(revision.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private long projectId(AiResourceRef r) {
        if (r.kind().equals("PROJECT") || COLLECTIONS.containsKey(r.kind())) return r.id();
        String sql = r.kind().equals("COMMENT")
                ? "select t.project_id from public.comments r join public.tasks t on t.id=r.task_id where r.id=:id"
                : "select project_id from public."+table(r.kind())+" where id=:id";
        List<?> ids = rows(sql,r.id());
        if (ids.isEmpty()) throw new BusinessException(404,"Resource not found");
        return ((Number)ids.getFirst()).longValue();
    }
    private String table(String kind) {
        String table=TABLES.get(kind);
        if (table==null) throw new BusinessException(400,"Unsupported resource kind");
        return table;
    }
    private List<?> rows(String sql,long id) { return em.createNativeQuery(sql).setParameter("id",id).getResultList(); }
}
