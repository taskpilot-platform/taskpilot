package com.taskpilot.ai.dto;

/**
 * Indicates the origin of a recommendation decision event.
 * USER: Explicit decision made by an authenticated project manager.
 * SYSTEM: Automated transition triggered by system workflows (e.g. expiration).
 */
public enum RecommendationDecisionSource {
    USER,
    SYSTEM
}
