package com.taskpilot.ai.tools.selection;

import com.taskpilot.ai.service.ToolMeta;
import com.taskpilot.ai.service.ToolScope;

import java.util.Collection;
import java.util.Set;

public interface SelectionRule {
    boolean isApplicable(String normalizedMessage, Set<ToolScope> activeScopes, boolean isWriteIntent);
    int adjustScore(ToolMeta tool, String normalizedMessage, Set<ToolScope> activeScopes, boolean isWriteIntent);
    boolean shouldForceInclude(ToolMeta tool, String normalizedMessage, Set<ToolScope> activeScopes);
    boolean shouldForceExclude(ToolMeta tool, String normalizedMessage, Set<ToolScope> activeScopes);
}
