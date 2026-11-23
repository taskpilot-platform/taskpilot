package com.taskpilot.contracts.task;

import java.util.List;

/**
 * Canonical quality-v1 rubric anchors shared across backend validation, DTOs, and UI.
 */
public final class TaskQualityRubric {

    public static final String RUBRIC_VERSION = "quality-v1";

    public record LevelAnchor(int rating, String label, String description) {
    }

    public static final List<LevelAnchor> QUALITY_V1_ANCHORS = List.of(
            new LevelAnchor(1, "1 - Major corrections",
                    "Accepted only after major substantive correction or multiple failures of critical agreed criteria; final verification evidence recorded."),
            new LevelAnchor(2, "2 - Material corrections",
                    "Accepted after material corrections affecting an agreed core requirement; fewer or less severe issues than level 1, with concrete review references."),
            new LevelAnchor(3, "3 - Minor corrections",
                    "Meets all agreed criteria after minor corrections; no material gap remained; standard verification evidence provided."),
            new LevelAnchor(4, "4 - First substantive pass",
                    "Meets all agreed criteria on first substantive review, with complete expected verification/documentation; only cosmetic nits if any."),
            new LevelAnchor(5, "5 - Verified edge cases",
                    "Level 4 plus explicit evidence that agreed high-risk/edge-case checks were independently verified; those checks were part of the recorded acceptance criteria before submission, not newly invented stretch work."));

    private TaskQualityRubric() {
    }

    public static LevelAnchor anchorFor(int rating) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("qualityRating must be between 1 and 5");
        }
        return QUALITY_V1_ANCHORS.get(rating - 1);
    }
}
