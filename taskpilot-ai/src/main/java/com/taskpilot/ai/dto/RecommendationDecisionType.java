package com.taskpilot.ai.dto;

/**
 * Structured PM decision types for Phase 2B.
 * Tracks terminal human-in-the-loop decisions linked to recommendation snapshots.
 */
public enum RecommendationDecisionType {
    ACCEPTED,
    OVERRIDDEN,
    REJECTED,
    CANCELED,
    EXPIRED
}
