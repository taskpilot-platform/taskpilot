package com.taskpilot.ai.heuristic;

import com.taskpilot.ai.dto.MetricDataStatus;
import com.taskpilot.ai.dto.RecommendationDifferentiationStatus;
import com.taskpilot.ai.dto.RecommendedCandidateView;
import com.taskpilot.ai.service.AutoAssignmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 B2: LLM Explanation Safety Contract Tests.
 *
 * Enforces Decisions H-010, H-011, H-013, H-015, H-016:
 * - Prompts given to LLM for generating explanations must consume ONLY allowlisted presentation fields.
 * - Forbidden internal fields (totalScore, confidenceScore, weights, loadScore, email, internal IDs) must be ABSENT.
 * - Approved Vietnamese wording must accompany UNVERIFIED workload and DEFAULT performance.
 * - Fit status must distinguish MEASURED from INSUFFICIENT_DATA.
 * - INSUFFICIENT_TO_DIFFERENTIATE condition must warn against claiming superiority.
 * - Fallback explanation must be safe and status-aware.
 */
class B2ExplanationSafetyTest {

    // AutoAssignmentService without Spring context, instantiating null dependencies because
    // buildExplanationPrompt is a deterministic prompt formatting function.
    private final AutoAssignmentService service = new AutoAssignmentService(
            null, null, null, null, null, null, null
    );

    @Test
    @DisplayName("B2 Requirement 1: Prompt generation strictly excludes forbidden internal fields")
    void prompt_excludesForbiddenInternalFields() {
        RecommendedCandidateView cand1 = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(101L)
                .displayName("Alice Engineer")
                .presentationFitValue(0.85)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(20)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        String prompt = service.buildExplanationPrompt(
                List.of(cand1),
                List.of("Java", "Spring Boot"),
                5,
                RecommendationDifferentiationStatus.DIFFERENTIATED
        );

        assertNotNull(prompt);
        // Forbidden fields checks
        assertFalse(prompt.contains("totalScore"), "Forbidden field totalScore leaked into prompt");
        assertFalse(prompt.contains("confidenceScore"), "Forbidden field confidenceScore leaked into prompt");
        assertFalse(prompt.contains("confidence"), "Forbidden field confidence leaked into prompt");
        assertFalse(prompt.contains("loadScore"), "Forbidden field loadScore leaked into prompt");
        assertFalse(prompt.contains("performanceScore"), "Forbidden field performanceScore leaked into prompt");
        assertFalse(prompt.contains("fitScore"), "Forbidden field fitScore leaked into prompt");
        assertFalse(prompt.contains("email"), "Forbidden field email leaked into prompt");
        assertFalse(prompt.contains("rawPerformanceScore"), "Forbidden field rawPerformanceScore leaked into prompt");
        assertFalse(prompt.contains("relative-explanation-safe-v2"), "Superseded token present");
    }

    @Test
    @DisplayName("B2 Requirement 2: Prompt contains approved Vietnamese wording for UNVERIFIED and DEFAULT statuses")
    void prompt_containsApprovedVietnameseWording() {
        RecommendedCandidateView cand = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(101L)
                .displayName("Bob Developer")
                .presentationFitValue(0.70)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(40)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        String prompt = service.buildExplanationPrompt(
                List.of(cand),
                List.of("React"),
                3,
                RecommendationDifferentiationStatus.DIFFERENTIATED
        );

        assertTrue(prompt.contains("Chưa có dữ liệu workload đáng tin cậy"),
                "Prompt must contain approved workload wording");
        assertTrue(prompt.contains("Chưa đủ dữ liệu hiệu suất"),
                "Prompt must contain approved performance wording");
        assertTrue(prompt.contains("DEFAULT: Chưa đủ dữ liệu hiệu suất"),
                "Prompt must instruct LLM about DEFAULT baseline");
    }

    @Test
    @DisplayName("B2 Requirement 3: Prompt distinguishes MEASURED Fit from INSUFFICIENT_DATA")
    void prompt_distinguishesFitStatuses() {
        RecommendedCandidateView measuredCand = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(101L)
                .displayName("Charlie")
                .presentationFitValue(0.90)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(10)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        RecommendedCandidateView missingCand = RecommendedCandidateView.builder()
                .rank(2)
                .candidateId(102L)
                .displayName("Dana")
                .presentationFitValue(null)
                .fitStatus(MetricDataStatus.INSUFFICIENT_DATA)
                .storedWorkloadValue(10)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        String prompt = service.buildExplanationPrompt(
                List.of(measuredCand, missingCand),
                List.of("Python"),
                4,
                RecommendationDifferentiationStatus.DIFFERENTIATED
        );

        assertTrue(prompt.contains("90% (MEASURED)"), "Measured candidate should show percentage with MEASURED tag");
        assertTrue(prompt.contains("Chưa có dữ liệu kỹ năng (INSUFFICIENT_DATA)"),
                "Missing candidate should clearly state INSUFFICIENT_DATA");
    }

    @Test
    @DisplayName("B2 Requirement 4: When differentiationStatus is INSUFFICIENT_TO_DIFFERENTIATE, prompt forbids claiming superiority")
    void prompt_insufficientToDifferentiate_containsNoSuperiorityNotice() {
        RecommendedCandidateView cand1 = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(101L)
                .displayName("Evan")
                .presentationFitValue(null)
                .fitStatus(MetricDataStatus.INSUFFICIENT_DATA)
                .storedWorkloadValue(0)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        RecommendedCandidateView cand2 = RecommendedCandidateView.builder()
                .rank(2)
                .candidateId(102L)
                .displayName("Fiona")
                .presentationFitValue(null)
                .fitStatus(MetricDataStatus.INSUFFICIENT_DATA)
                .storedWorkloadValue(0)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        String prompt = service.buildExplanationPrompt(
                List.of(cand1, cand2),
                List.of(),
                5,
                RecommendationDifferentiationStatus.INSUFFICIENT_TO_DIFFERENTIATE
        );

        assertTrue(prompt.contains("KHÔNG ĐỦ để phân biệt ai vượt trội hơn"),
                "Prompt must warn about lack of distinguishing data");
        assertTrue(prompt.contains("Tuyệt đối KHÔNG khẳng định ứng viên nào là 'tốt nhất'"),
                "Prompt must forbid claiming first candidate is superior");
    }

    @Test
    @DisplayName("B2 Requirement 5: DEFAULT_FALLBACK_EXPLANATION adheres to explanation safety invariants")
    void fallbackExplanation_adheresToSafetyInvariants() {
        String fallback = AutoAssignmentService.DEFAULT_FALLBACK_EXPLANATION;

        assertNotNull(fallback);
        assertFalse(fallback.isBlank());
        // Must NOT claim historical performance when backed by default 0.5 baseline (H-011, H-015)
        assertFalse(fallback.contains("hiệu suất lịch sử"),
                "Fallback must NOT claim historical performance from default 0.5 baseline");
        // Must NOT refer to detailed scores since scores are omitted from user-facing allowlist (H-010)
        assertFalse(fallback.contains("điểm số chi tiết"),
                "Fallback must NOT refer to hidden detailed scores");
        // Must mention unverified workload status or default baseline
        assertTrue(fallback.contains("chưa có chứng thực độc lập") || fallback.contains("mặc định ban đầu"),
                "Fallback must reflect data status realities");
    }
}
