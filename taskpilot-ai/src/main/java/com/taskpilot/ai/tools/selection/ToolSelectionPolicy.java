package com.taskpilot.ai.tools.selection;

import com.taskpilot.ai.service.ToolMeta;
import com.taskpilot.ai.service.ToolScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ToolSelectionPolicy {

    private static final Logger log = LoggerFactory.getLogger(ToolSelectionPolicy.class);

    public static final int DEFAULT_MAX_TOOLS = 12;
    public static final int EXPANDED_MAX_TOOLS = 15;

    private static final Set<String> CORE_ESSENTIAL_TOOLS = Set.of(
        "queryProjects",
        "confirmPendingAction",
        "cancelPendingAction"
    );

    private static final Set<String> REDUNDANT_UPDATE_TOOLS = Set.of(
        "updateProject",
        "updateTask",
        "updateSprint",
        "updateTaskComment"
    );

    public List<String> selectTools(
            String message,
            Map<String, ToolMeta> allTools,
            Set<ToolScope> detectedScopes,
            boolean isWriteIntent,
            boolean expanded,
            Long currentProjectId) {

        int maxTools = expanded ? EXPANDED_MAX_TOOLS : DEFAULT_MAX_TOOLS;
        String normalized = KeywordMatcher.normalize(message);

        Set<String> selected = new LinkedHashSet<>();

        // 1. Always include core essential tools that exist in registry
        for (String core : CORE_ESSENTIAL_TOOLS) {
            if (allTools.containsKey(core)) {
                selected.add(core);
            }
        }

        // 2. Knowledge / Document inquiry heuristic
        boolean isKnowledgeInquiry = detectedScopes.contains(ToolScope.KNOWLEDGE)
            || KeywordMatcher.matchesAny(normalized, List.of(
                "tai lieu", "quy dinh", "quy trinh", "dac ta", "chinh sach", "noi quy", "huong dan", "spec", "rag"
            ))
            || normalized.contains("?")
            || KeywordMatcher.matchesAny(normalized, List.of("la gi", "the nao", "nhu the nao", "o dau", "sao"));

        if (isKnowledgeInquiry && allTools.containsKey("searchProjectKnowledge")) {
            selected.add("searchProjectKnowledge");
        }

        // 3. Disambiguation rules
        boolean isAnnouncementIntent = KeywordMatcher.matchesAny(normalized, List.of("thong bao", "announce"))
            && KeywordMatcher.matchesAny(normalized, List.of("gui", "dang", "len", "cho nhom", "kenh", "chat", "broadcast"));

        boolean isMyNotificationIntent = KeywordMatcher.matchesAny(normalized, List.of("thong bao"))
            && KeywordMatcher.matchesAny(normalized, List.of("cua toi", "cua minh", "chua doc", "moi", "danh dau"));

        // 4. Score all available tools
        List<ScoredCandidate> scoredList = new ArrayList<>();

        for (ToolMeta meta : allTools.values()) {
            String name = meta.toolName();

            // Skip redundant full-update tools in favor of patch
            if (REDUNDANT_UPDATE_TOOLS.contains(name)) {
                continue;
            }

            // Exclude notification/chat crossed tools
            if (isAnnouncementIntent && name.equals("getMyNotifications")) {
                continue;
            }
            if (isMyNotificationIntent && name.equals("sendChannelAnnouncement")) {
                continue;
            }

            // Disambiguate meeting vs member recommendation ("phù hợp" vs "họp")
            if (name.equals("scheduleMeeting") || name.equals("listProjectMeetings") || name.equals("summarizeMeeting")) {
                boolean hasExplicitMeeting = KeywordMatcher.matchesAny(normalized, List.of("cuoc hop", "lich hop", "meeting", "buoi hop", "bien ban", "recording", "ban ghi"));
                if (!hasExplicitMeeting) {
                    continue;
                }
            }

            // If not write intent, filter out dangerous write tools
            if (!isWriteIntent && !meta.readOnly() && !meta.essential()) {
                continue;
            }

            int score = meta.priorityScore();

            // Boost if scopes match
            boolean scopeMatched = false;
            if (meta.scopes() != null && detectedScopes != null) {
                for (ToolScope scope : meta.scopes()) {
                    if (detectedScopes.contains(scope)) {
                        score += 30;
                        scopeMatched = true;
                        break;
                    }
                }
            }

            // Keyword matches
            int kwMatches = KeywordMatcher.countMatches(normalized, meta.keywords());
            score += kwMatches * 15;

            // Must have an explicit relevance signal (keyword match or scope match) unless essential
            if (!meta.essential() && kwMatches == 0 && !scopeMatched) {
                continue;
            }

            // Write intent boost
            if (isWriteIntent && !meta.readOnly()) {
                score += 25;
            }

            scoredList.add(new ScoredCandidate(name, score));
        }

        // Sort scored candidates descending
        scoredList.sort(Comparator.comparingInt(ScoredCandidate::score).reversed());

        // Add to selected until maxTools reached
        for (ScoredCandidate cand : scoredList) {
            if (selected.size() >= maxTools) {
                break;
            }
            selected.add(cand.name());
        }

        log.info("[ToolSelectionPolicy] Selected {} tools (max={}) for message: \"{}\" -> {}",
                selected.size(), maxTools, normalized.length() > 50 ? normalized.substring(0, 50) + "..." : normalized, selected);

        return new ArrayList<>(selected);
    }

    private record ScoredCandidate(String name, int score) {}
}
