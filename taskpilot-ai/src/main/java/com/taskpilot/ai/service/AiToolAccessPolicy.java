package com.taskpilot.ai.service;

import com.taskpilot.ai.tools.support.AiToolSupport;
import com.taskpilot.contracts.aiquery.dto.AiResourceRef;
import com.taskpilot.contracts.aiquery.port.out.AiResourceAccessPort;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.infrastructure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Revalidation happens before every read-cache hit and every explicit mutation execution. */
@Service @RequiredArgsConstructor
public class AiToolAccessPolicy {
    private final ChatSessionService sessions;
    private final AccountAuthStatePort accounts;
    private final AiResourceAccessPort resources;
    private final SkillPort skills;
    private static final Map<String,String> IDS=Map.of("projectId","PROJECT","taskId","TASK",
            "parentTaskId","TASK","parentId","TASK","sprintId","SPRINT","commentId","COMMENT",
            "fileId","FILE","meetingId","MEETING","labelId","LABEL");
    private static final Map<String,String> CACHED = Map.ofEntries(
            Map.entry("queryProjects","OWN_PROJECTS"),Map.entry("getTaskDetails","TASK"),
            Map.entry("queryTasks","TASKS"),Map.entry("getSprintsByProject","SPRINTS"),
            Map.entry("queryProjectMembers","MEMBERS"),Map.entry("getProjectLabels","LABELS"),
            Map.entry("listProjectFiles","FILES"),Map.entry("searchProjectFiles","FILES"),
            Map.entry("listProjectMeetings","MEETINGS"),Map.entry("getProjectStatus","PROJECT"));

    private void collectResources(Map<?,?> args,List<AiResourceRef> refs) {
        IDS.forEach((key,kind) -> { Long id=AiToolSupport.toLong(args.get(key)); if(id!=null) refs.add(new AiResourceRef(kind,id)); });
        if(args.get("labelIds") instanceof Collection<?> ids) for(Object value:ids) {
            Long id=AiToolSupport.toLong(value); if(id!=null) refs.add(new AiResourceRef("LABEL",id));
        }
    }
    public void authorizeSession(Long userId,Long sessionId) {
        if (userId==null || sessionId==null) throw new BusinessException(401,"Authenticated chat session required");
        var account=accounts.findById(userId).orElseThrow(() -> new BusinessException(401,"Account not found"));
        if (!"ACTIVE".equals(account.lifecycle()) || !account.emailVerified()) throw new BusinessException(403,"Account is not active");
        sessions.getSession(sessionId,userId);
    }
    public boolean cacheable(String tool) { return CACHED.containsKey(tool); }
    public String fingerprint(String tool,Map<String,Object> args,Long userId,Long sessionId,boolean lock) {
        authorizeSession(userId,sessionId);
        List<AiResourceRef> refs=new ArrayList<>();
        collectResources(args, refs);
        if(args.get("patch") instanceof Map<?,?> patch) collectResources(patch,refs);
        String collection=CACHED.get(tool);
        if ("OWN_PROJECTS".equals(collection)) refs.add(new AiResourceRef(collection,userId));
        else if (collection!=null && collection.endsWith("S") && args.get("projectId")!=null)
            refs.add(new AiResourceRef(collection,AiToolSupport.toLong(args.get("projectId"))));
        List<Long> targets=new ArrayList<>();
        Object assignee=cacheable(tool) ? null : args.get("assigneeId");
        if("assignTaskToMember".equals(tool) || "recommendAndAssignTask".equals(tool)) assignee=args.get("memberId");
        if (args.get("patch") instanceof Map<?,?> patch && patch.containsKey("assigneeId")) assignee=patch.get("assigneeId");
        Long target=AiToolSupport.toLong(assignee); if(target!=null) targets.add(target);
        if(args.get("actionItems") instanceof Collection<?> items) for(Object item:items) {
            if(item instanceof Map<?,?> map) {
                Long id=AiToolSupport.toLong(map.get("assigneeId")); if(id!=null) targets.add(id);
            }
        }
        String revision=resources.fingerprint(userId,refs,targets,lock);
        Long skillId=AiToolSupport.toLong(args.get("skillId"));
        if (skillId!=null) revision += ":"+skills.findSkillById(skillId)+":"+skills.getMySkills(userId);
        return revision;
    }
}
