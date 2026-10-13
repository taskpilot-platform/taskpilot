package com.taskpilot.ai.dto;

/**
 * Recommendation differentiation status for Phase 1 candidate ranking.
 *
 * Enforces Decision H-013:
 * - DIFFERENTIATED: Candidates are meaningfully distinguished by scoring criteria or tie-breaks.
 * - INSUFFICIENT_TO_DIFFERENTIATE: Applicable ranking evidence cannot distinguish candidates.
 * - UNKNOWN: Differentiation state cannot be determined prior to fixed-point comparator implementation.
 */
public enum RecommendationDifferentiationStatus {
    DIFFERENTIATED,
    INSUFFICIENT_TO_DIFFERENTIATE,
    UNKNOWN
}
