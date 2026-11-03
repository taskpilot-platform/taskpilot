package com.taskpilot.ai.service;

import java.util.List;
import java.util.Set;

public record ToolMeta(
    String toolName,
    Set<ToolScope> scopes,
    List<String> keywords,
    int priorityScore,
    boolean essential,
    boolean readOnly,
    boolean cacheable,
    boolean requiresProjectId
) {
    public ToolMeta(String toolName, Set<ToolScope> scopes, List<String> keywords, int priorityScore, boolean essential) {
        this(toolName, scopes, keywords, priorityScore, essential, true, true, false);
    }
}
