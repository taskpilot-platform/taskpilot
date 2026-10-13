package com.taskpilot.ai.heuristic;

import com.taskpilot.ai.service.AutoAssignmentService;
import com.taskpilot.contracts.assignment.dto.UserSkillDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Characterization Tests: Skill Fit Semantics
 *
 * Covers Item 8:
 * - measured match
 * - measured zero match
 * - missing member skills
 * - missing task-required skills (Fit semantics F4 / F5, defect: returns 1.0)
 * - null level mapped to zero (NPE on unboxing)
 * - level greater than five (defect: exceeds 1.0)
 * - duplicate required skills (defect: distorts matchRatio)
 * - duplicate member skills (retains first level, ignores subsequent)
 *
 * Rules:
 * - Direct invocation of production AutoAssignmentService.calculateFitScore via reflection.
 * - Zero mocks, zero Spring context, zero external dependencies.
 */
class SkillFitSemanticsCharacterizationTest {

    private AutoAssignmentService service;
    private Method calculateFitScoreMethod;

    @BeforeEach
    void setUp() throws Exception {
        // Instantiate AutoAssignmentService with dummy values (no mock framework needed)
        service = new AutoAssignmentService(null, null, null, null, null, null, null, 5000L, "gemini-3.5-flash");
        calculateFitScoreMethod = AutoAssignmentService.class.getDeclaredMethod("calculateFitScore", List.class, List.class);
        calculateFitScoreMethod.setAccessible(true);
    }

    private double invokeCalculateFitScore(List<UserSkillDto> userSkills, List<String> requiredSkills) throws Exception {
        try {
            return (double) calculateFitScoreMethod.invoke(service, userSkills, requiredSkills);
        } catch (InvocationTargetException ite) {
            if (ite.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw ite;
        }
    }

    // =========================================================================
    // 8.1 Measured Match
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: measured match computes weighted match ratio (0.6) and level normalization (0.4)")
    void currentBehavior_fitScore_measuredMatch() throws Exception {
        // Required: Java, Spring
        // Member: Java (level 5), Spring (level 4)
        List<UserSkillDto> userSkills = List.of(
                new UserSkillDto("Java", 5),
                new UserSkillDto("Spring", 4)
        );
        List<String> requiredSkills = List.of("Java", "Spring");

        // matched = 2 / 2 = 1.0 (weight 0.6 -> 0.60)
        // totalLevel = 9 / (2 * 5.0) = 0.90 (weight 0.4 -> 0.36)
        // expected score = 0.60 + 0.36 = 0.96
        double score = invokeCalculateFitScore(userSkills, requiredSkills);

        assertEquals(0.96, score, 1e-4);
    }

    // =========================================================================
    // 8.2 Measured Zero Match
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: measured zero match returns 0.0 when member has skills but none match task")
    void currentBehavior_fitScore_measuredZeroMatch() throws Exception {
        List<UserSkillDto> userSkills = List.of(
                new UserSkillDto("Python", 5),
                new UserSkillDto("Docker", 3)
        );
        List<String> requiredSkills = List.of("Java");

        double score = invokeCalculateFitScore(userSkills, requiredSkills);

        assertEquals(0.0, score, 1e-9);
    }

    // =========================================================================
    // 8.3 Missing Member Skills
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: missing member skills returns 0.0, currently indistinguishable from measured zero match (defect F3)")
    void currentBehavior_fitScore_missingMemberSkills_indistinguishableFromZeroMatchDefect() throws Exception {
        // Member has an empty skill profile
        List<UserSkillDto> emptySkills = Collections.emptyList();
        List<String> requiredSkills = List.of("Java");

        double score = invokeCalculateFitScore(emptySkills, requiredSkills);

        // Characterizes defect: returns 0.0 without signaling INSUFFICIENT_DATA
        assertEquals(0.0, score, 1e-9);
    }

    // =========================================================================
    // 8.4 Missing Task-Required Skills (Fit Semantics F4 / F5)
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: missing task requirements defectively returns 1.0 perfect match (Fit semantics F4/F5)")
    void currentBehavior_fitScore_missingTaskRequiredSkills_returnsOnePointZeroDefect() throws Exception {
        List<UserSkillDto> userSkills = List.of(new UserSkillDto("Java", 3));

        // When task requirements are empty or null
        double emptyScore = invokeCalculateFitScore(userSkills, Collections.emptyList());
        double nullScore = invokeCalculateFitScore(userSkills, null);

        // Characterizes defect: claims 100% skill match (1.0) when requirements are absent!
        assertEquals(1.0, emptyScore, 1e-9, "Empty requirements defectively claim 1.0 fit");
        assertEquals(1.0, nullScore, 1e-9, "Null requirements defectively claim 1.0 fit");
    }

    // =========================================================================
    // 8.5 Null Level Mapped to Zero / NPE on unboxing
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: null skill list causes NullPointerException in stream processing")
    void currentBehavior_fitScore_nullUserSkills_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> {
            invokeCalculateFitScore(null, List.of("Java"));
        });
    }

    // =========================================================================
    // 8.6 Level Greater than Five
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: skill level greater than 5 causes fit score to exceed 1.0 due to lack of clamping")
    void currentBehavior_fitScore_levelGreaterThanFive_exceedsOnePointZeroDefect() throws Exception {
        // Member has Java level 7 (exceeds theoretical maximum of 5)
        List<UserSkillDto> userSkills = List.of(new UserSkillDto("Java", 7));
        List<String> requiredSkills = List.of("Java");

        // matchRatio = 1.0 (weight 0.6 -> 0.60)
        // avgLevelNormalized = 7 / (1 * 5.0) = 1.40 (weight 0.4 -> 0.56)
        // score = 0.60 + 0.56 = 1.16 > 1.0
        double score = invokeCalculateFitScore(userSkills, requiredSkills);

        assertTrue(score > 1.0, "Score exceeds 1.0 because level is not clamped to [0, 5]");
        assertEquals(1.16, score, 1e-4);
    }

    // =========================================================================
    // 8.7 Duplicate Required Skills
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: duplicate required skills distort matchRatio and count")
    void currentBehavior_fitScore_duplicateRequiredSkills_distortsMatchRatioDefect() throws Exception {
        // Task has duplicate "Java" in required skills: ["Java", "Java", "Python"]
        List<UserSkillDto> userSkills = List.of(new UserSkillDto("Java", 5));
        List<String> duplicatedRequirements = List.of("Java", "Java", "Python");

        // Loop matches "Java" twice: matched = 2
        // matchRatio = 2 / 3.0 = 0.667 instead of unique match 1 / 2 = 0.50
        double score = invokeCalculateFitScore(userSkills, duplicatedRequirements);

        // totalLevel = 5 + 5 = 10 / (2 * 5.0) = 1.0
        // (0.6667 * 0.6) + (1.0 * 0.4) = 0.4000 + 0.4000 = 0.8000
        assertEquals(0.80, score, 1e-4);
    }

    // =========================================================================
    // 8.8 Duplicate Member Skills
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: duplicate member skills retain first level and ignore subsequent (Collectors.toMap merge (a, b) -> a)")
    void currentBehavior_fitScore_duplicateMemberSkills_retainsFirstLevelIgnoredSubsequent() throws Exception {
        // User has duplicate "Java" entries with different levels: 3 first, then 5
        List<UserSkillDto> userSkillsFirst3Then5 = List.of(
                new UserSkillDto("Java", 3),
                new UserSkillDto("Java", 5)
        );
        List<String> requiredSkills = List.of("Java");

        // Collectors.toMap(..., (a, b) -> a) retains first entry (level 3)
        // matchRatio = 1.0 -> 0.60
        // avgLevel = 3 / 5.0 = 0.60 -> 0.24
        // score = 0.84
        double score1 = invokeCalculateFitScore(userSkillsFirst3Then5, requiredSkills);
        assertEquals(0.84, score1, 1e-9, "Retains first level (3) yielding 0.84");

        // Conversely, if level 5 is first and level 3 is second
        List<UserSkillDto> userSkillsFirst5Then3 = List.of(
                new UserSkillDto("Java", 5),
                new UserSkillDto("Java", 3)
        );
        // matchRatio = 1.0 -> 0.60, avgLevel = 5 / 5.0 = 1.00 -> 0.40, score = 1.00
        double score2 = invokeCalculateFitScore(userSkillsFirst5Then3, requiredSkills);
        assertEquals(1.00, score2, 1e-9, "Retains first level (5) yielding 1.00");
    }
}

