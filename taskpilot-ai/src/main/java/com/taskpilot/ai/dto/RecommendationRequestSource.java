package com.taskpilot.ai.dto;

/**
 * Identifies the entry point from which a recommendation snapshot was requested.
 */
public enum RecommendationRequestSource {
    REST,
    AI_TOOL_PROJECT_RECOMMENDATION,
    AI_TOOL_TASK_RECOMMENDATION,
    AI_TOOL_RECOMMEND_AND_ASSIGN
}
