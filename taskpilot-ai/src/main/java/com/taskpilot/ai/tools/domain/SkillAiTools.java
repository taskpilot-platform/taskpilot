package com.taskpilot.ai.tools.domain;

import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.PendingAiActionService;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.taskpilot.ai.tools.support.AiToolSupport.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SkillAiTools {

    private final SkillPort skillPort;
    private final PendingAiActionService pendingAiActionService;

    @Tool("Search the global system skill directory by keyword (use empty string to list default active skills).")
    public List<SkillDto> searchSystemSkills(
            @P("Skill search keyword. Use empty string to list common active skills.") String keyword) {
        String safeKeyword = keyword == null ? "" : keyword.trim();
        log.info("[AiTool] searchSystemSkills called keyword='{}'", safeKeyword);
        return skillPort.search(safeKeyword);
    }


    @Tool("Create a new system skill in the shared skill directory. Requires confirmation.")
    public Object createSystemSkill(
            @P("Skill name") String name,
            @P("Optional skill description") String description) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] createSystemSkill called name={}", name);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "createSystemSkill",
                "Create system skill \"" + name + "\"",
                args("name", name, "description", description),
                args("name", name, "description", description),
                () -> skillPort.createSystemSkill(name, description, userId));
    }


    @Tool("Partially update a system skill. Send patchData map containing changed fields (name, description). Requires confirmation.")
    public Object patchSystemSkill(
            @P("The ID of the skill") Long skillId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        Map<String, Object> patch = normalizePatch(patchData);
        log.info("[AiTool] patchSystemSkill called for skill {} patch {}", skillId, patch);
        patch.keySet().forEach(fieldName -> validatePatchField(fieldName, Set.of("name", "description")));
        String name = stringPatchValue(patch, "name");
        String description = stringPatchValue(patch, "description");
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        return pendingAiActionService.create(
                userId,
                sessionId,
                "patchSystemSkill",
                "Patch system skill " + skillId,
                args("skillId", skillId, "patch", patch, "reason", reason),
                args("skillId", skillId, "patch", patch, "reason", reason),
                () -> skillPort.patchSystemSkill(skillId, name, description, userId));
    }


    @Tool("Delete or deactivate a system skill in the shared directory by skill ID. Requires confirmation.")
    public Object deleteSystemSkill(@P("System skill ID to deactivate") Long skillId) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] deleteSystemSkill called for skill {}", skillId);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteSystemSkill",
                "Delete system skill " + skillId,
                args("skillId", skillId),
                null,
                () -> {
                    skillPort.deleteSystemSkill(skillId, userId);
                    return "System skill deleted successfully";
                });
    }


    @Tool("List the current user's personal skills.")
    public Object getMySkills() {
        Long userId = ToolExecutionContext.requireUserId();
        log.info("[AiTool] getMySkills called for user {}", userId);
        return skillPort.getMySkills(userId);
    }


    @Tool("Add a system skill that does NOT exist in the current user's personal skills with a level (1-5). CRITICAL: If the user already has this skill and you want to update or change its level, you MUST call patchMySkill instead! If adding common skills like Java, use the ID mentioned in parameter descriptions (e.g. 1 for Java) directly! DO NOT call searchSystemSkills to look up ID. Requires confirmation.")
    public Object addMySkill(
            @P("System skill ID (e.g. 1 for Java)") Long skillId,
            @P("Skill level from 1 to 5") Integer level) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] addMySkill called for skill {} level {}", skillId, level);
        int safeLevel = clampSkillLevel(level);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "addMySkill",
                "Add skill " + skillId + " at level " + safeLevel,
                args("skillId", skillId, "level", safeLevel),
                args("skillId", skillId, "level", safeLevel),
                () -> skillPort.addMySkill(skillId, safeLevel, userId));
    }


    @Tool("Partially update an existing personal skill (e.g. update its level). Send patchData map containing changed fields (level 1-5). CRITICAL: If you want to update or change the level of a skill the user already has, you MUST call this tool (patchMySkill) instead of addMySkill! Requires confirmation.")
    public Object patchMySkill(
            @P("The ID of the skill (e.g. 1 for Java)") Long skillId,
            @P("Map containing only changed fields") Object patchData,
            @P("Optional reason for the change") String reason) {
        Map<String, Object> patch = normalizePatch(patchData);
        log.info("[AiTool] patchMySkill called for skill {} patch {}", skillId, patch);
        patch.keySet().forEach(fieldName -> validatePatchField(fieldName, Set.of("level")));
        Integer level = integerPatchValue(patch, "level");
        if (level == null) {
            throw new IllegalArgumentException("patchMySkill requires level in patch.");
        }
        int safeLevel = clampSkillLevel(level);
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        // note: AI doesn't know context skill ID usually, so this is mostly if AI knows the user's skill mapping.
        return pendingAiActionService.create(
                userId,
                sessionId,
                "patchMySkill",
                "Patch my skill " + skillId,
                args("skillId", skillId, "patch", args("level", safeLevel), "reason", reason),
                args("skillId", skillId, "patch", args("level", safeLevel), "reason", reason),
                () -> skillPort.updateMySkill(skillId, safeLevel, userId));
    }


    @Tool("Remove a skill from the current user's personal skills. Requires confirmation.")
    public Object deleteMySkill(@P("System skill ID to remove from my skills (e.g. 1 for Java)") Long skillId) {
        Long userId = ToolExecutionContext.requireUserId();
        Long sessionId = ToolExecutionContext.requireSessionId();
        log.info("[AiTool] deleteMySkill called for skill {}", skillId);
        return pendingAiActionService.create(
                userId,
                sessionId,
                "deleteMySkill",
                "Delete my skill " + skillId,
                args("skillId", skillId),
                null,
                () -> {
                    skillPort.deleteMySkill(skillId, userId);
                    return "Skill removed successfully";
                });
    }


}
