package com.taskpilot.ai.heuristic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 B1 Tests: Allowlisted User-Facing Recommendation Contract
 *
 * Verifies Gate 2 & Decisions H-001, H-010, H-011, H-014, H-015, H-016, H-019:
 * 1. Internal ranking object retains raw metrics and ranking state (rankingRawFit, derivedPerformanceInput, storedWorkloadValue).
 * 2. Presentation view contains ONLY allowlisted fields.
 * 3. Forbidden fields (email, confidenceScore, totalScore, loadScore, numeric performance, etc.) are ABSENT.
 * 4. Versioning: presentationContractVersion="allowlisted-view-v1", scoringModelVersion="relative-rounded-v1".
 * 5. differentiationStatus is unconditionally UNKNOWN for B1.
 * 6. Workload status is UNVERIFIED; Performance status is DEFAULT (with NO numeric performance exposed).
 * 7. Measured zero Fit is distinguished from missing task requirements (INSUFFICIENT_DATA).
 * 8. Single candidate raw Fit is preserved and not presented as normalized 100%.
 * 9. Full confirmation wrapper serialization (ConfirmationRequiredDto -> RecommendAndAssignResult -> RecommendationView).
 */
class AllowlistedRecommendationContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // =========================================================================
    // 1. Internal Ranking Representation Retains All Raw & Ranking State
    // =========================================================================

    @Test
    @DisplayName("B1 Requirement 1: Internal ranking object retains raw metrics, normalized scores, and full-precision score")
    void internalRankingObject_retainsRawMetricsAndInternalFields() {
        NormalizedScores normalized = new NormalizedScores(0.85, 0.20, 0.50);
        InternalCandidateRanking ranking = InternalCandidateRanking.builder()
                .userId(42L)
                .fullName("Alice Engineer")
                .email("alice@taskpilot.internal")
                .rankingRawFit(0.85)
                .presentationFitValue(0.85)
                .storedWorkloadValue(20)
                .derivedPerformanceInput(0.50)
                .normalizedScores(normalized)
                .fullPrecisionScore(0.3524)
                .roundedScore(0.35)
                .confidence(0.70)
                .status("AVAILABLE")
                .heuristicMode("BALANCED")
                .fitStatus(MetricDataStatus.MEASURED)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .build();

        assertEquals(42L, ranking.userId());
        assertEquals("Alice Engineer", ranking.fullName());
        assertEquals("alice@taskpilot.internal", ranking.email());
        assertEquals(0.85, ranking.rankingRawFit(), 1e-9);
        assertEquals(0.85, ranking.presentationFitValue(), 1e-9);
        assertEquals(20, ranking.storedWorkloadValue());
        assertEquals(0.50, ranking.derivedPerformanceInput(), 1e-9);
        assertEquals(normalized, ranking.normalizedScores());
        assertEquals(0.3524, ranking.fullPrecisionScore(), 1e-9);
        assertEquals(0.35, ranking.roundedScore(), 1e-9);
        assertEquals(0.70, ranking.confidence(), 1e-9);
        assertEquals(MetricDataStatus.MEASURED, ranking.fitStatus());
        assertEquals(MetricDataStatus.UNVERIFIED, ranking.workloadStatus());
        assertEquals(MetricDataStatus.DEFAULT, ranking.performanceStatus());
    }

    // =========================================================================
    // 2. Presentation Object Contains Only Allowlisted Fields & Excludes Forbidden Fields
    // =========================================================================

    @Test
    @DisplayName("B1 Requirements 2-9: Allowlisted view serializes approved fields and completely omits forbidden fields")
    void presentationObject_containsOnlyAllowlistedFields_andExcludesAllForbiddenFields() throws Exception {
        RecommendedCandidateView candidateView = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(42L)
                .displayName("Alice Engineer")
                .presentationFitValue(0.85)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(20)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        RecommendationView recommendationView = RecommendationView.builder()
                .projectId(10L)
                .requiredSkills(List.of("Java"))
                .candidates(List.of(candidateView))
                .differentiationStatus(RecommendationDifferentiationStatus.UNKNOWN)
                .presentationContractVersion(RecommendationView.PRESENTATION_CONTRACT_VERSION)
                .scoringModelVersion(RecommendationView.SCORING_MODEL_VERSION)
                .heuristicMode("BALANCED")
                .aiExplanation("Alice is recommended.")
                .build();

        String json = objectMapper.writeValueAsString(recommendationView);
        JsonNode root = objectMapper.readTree(json);

        // --- Root Level Allowlist Verification ---
        assertEquals(10L, root.path("projectId").asLong());
        assertEquals("Java", root.path("requiredSkills").get(0).asText());
        assertEquals("allowlisted-view-v1", root.path("presentationContractVersion").asText(),
                "presentationContractVersion must be allowlisted-view-v1 (H-014)");
        assertEquals("relative-neutral-fixed-point-v2", root.path("scoringModelVersion").asText(),
                "scoringModelVersion must be relative-neutral-fixed-point-v2 (H-014)");
        assertEquals("UNKNOWN", root.path("differentiationStatus").asText(),
                "differentiationStatus must be UNKNOWN for B1");
        assertEquals("BALANCED", root.path("heuristicMode").asText());
        assertEquals("Alice is recommended.", root.path("aiExplanation").asText());

        // --- Candidate Level Allowlist Verification ---
        JsonNode candidateNode = root.path("candidates").get(0);
        assertEquals(1, candidateNode.path("rank").asInt());
        assertEquals(42L, candidateNode.path("candidateId").asLong());
        assertEquals("Alice Engineer", candidateNode.path("displayName").asText());
        assertEquals(0.85, candidateNode.path("presentationFitValue").asDouble(), 1e-9);
        assertEquals("MEASURED", candidateNode.path("fitStatus").asText());
        assertEquals(20, candidateNode.path("storedWorkloadValue").asInt());
        assertEquals("UNVERIFIED", candidateNode.path("workloadStatus").asText());
        assertEquals("DEFAULT", candidateNode.path("performanceStatus").asText());
        assertEquals("AVAILABLE", candidateNode.path("memberStatus").asText());

        // --- Strict Forbidden Field Verification (Gate 2 / H-010 / H-016 / H-019) ---
        assertTrue(candidateNode.path("email").isMissingNode(), "FORBIDDEN: email must NOT be exposed");
        assertTrue(candidateNode.path("confidenceScore").isMissingNode(), "FORBIDDEN: confidenceScore must NOT be exposed (H-019)");
        assertTrue(candidateNode.path("confidence").isMissingNode(), "FORBIDDEN: confidence must NOT be exposed");
        assertTrue(candidateNode.path("totalScore").isMissingNode(), "FORBIDDEN: relative totalScore must NOT be exposed");
        assertTrue(candidateNode.path("fitScore").isMissingNode(), "FORBIDDEN: normalized fitScore must NOT be exposed");
        assertTrue(candidateNode.path("loadScore").isMissingNode(), "FORBIDDEN: normalized loadScore must NOT be exposed");
        assertTrue(candidateNode.path("performanceScore").isMissingNode(), "FORBIDDEN: performanceScore must NOT be exposed");
        assertTrue(candidateNode.path("rawPerformanceScore").isMissingNode(), "FORBIDDEN: rawPerformanceScore must NOT be exposed");
        assertTrue(candidateNode.path("rawPerformance").isMissingNode(), "FORBIDDEN: rawPerformance must NOT be exposed");
        assertTrue(candidateNode.path("derivedPerformanceInput").isMissingNode(), "FORBIDDEN: derivedPerformanceInput must NOT be exposed");
        assertTrue(candidateNode.path("skillScore").isMissingNode(), "FORBIDDEN: redundant skillScore must NOT be exposed");
        assertTrue(candidateNode.path("workloadScore").isMissingNode(), "FORBIDDEN: redundant workloadScore must NOT be exposed");
        assertTrue(candidateNode.path("rawWorkload").isMissingNode(), "FORBIDDEN: rawWorkload must NOT be exposed (use storedWorkloadValue)");
        assertTrue(candidateNode.path("rawFitScore").isMissingNode(), "FORBIDDEN: rawFitScore must NOT be exposed (use presentationFitValue)");
        assertTrue(candidateNode.path("rankingRawFit").isMissingNode(), "FORBIDDEN: rankingRawFit must NOT be exposed");
        assertTrue(candidateNode.path("fullPrecisionScore").isMissingNode(), "FORBIDDEN: full-precision score must NOT be exposed");
        assertTrue(candidateNode.path("rankingKey").isMissingNode(), "FORBIDDEN: rankingKey must NOT be exposed");
        assertTrue(candidateNode.path("configuredWeights").isMissingNode(), "FORBIDDEN: configured weights must NOT be exposed");
        assertTrue(candidateNode.path("weights").isMissingNode(), "FORBIDDEN: weights must NOT be exposed");
        assertTrue(candidateNode.path("normalizedScores").isMissingNode(), "FORBIDDEN: normalizedScores object must NOT be exposed");

        // Verify draft string relative-explanation-safe-v2 is purged
        assertFalse(json.contains("relative-explanation-safe-v2"),
                "Draft version string relative-explanation-safe-v2 must be completely purged");
    }

    // =========================================================================
    // 3. Raw Skill Fit Contract: Measured vs Insufficient Data
    // =========================================================================

    @Test
    @DisplayName("B1 Requirement 12: Skill Fit distinguishes MEASURED zero match from missing task requirements (INSUFFICIENT_DATA)")
    void skillFitStatus_measuredZeroMatch_vs_insufficientData() throws Exception {
        // Case 1: Task has requirements ["Java"], member has Java level 0 (zero match) -> presentationFitValue is 0.0, MEASURED
        RecommendedCandidateView zeroMatchCandidate = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(101L)
                .displayName("Bob Junior")
                .presentationFitValue(0.0)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(10)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .build();

        assertEquals(0.0, zeroMatchCandidate.presentationFitValue(), 1e-9);
        assertEquals(MetricDataStatus.MEASURED, zeroMatchCandidate.fitStatus(),
                "Measured zero match has status MEASURED and value 0.0");

        // Case 2: Task has NO requirements (empty list) -> INSUFFICIENT_DATA, presentationFitValue is null!
        RecommendedCandidateView noReqsCandidate = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(102L)
                .displayName("Charlie Architect")
                .presentationFitValue(null)
                .fitStatus(MetricDataStatus.INSUFFICIENT_DATA)
                .storedWorkloadValue(15)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .build();

        assertNull(noReqsCandidate.presentationFitValue(),
                "Missing task requirements must yield null presentationFitValue");
        assertEquals(MetricDataStatus.INSUFFICIENT_DATA, noReqsCandidate.fitStatus(),
                "Missing task requirements must NOT be presented as measured Fit 100% (H-011)");

        // Verify JSON omits null presentationFitValue under NON_NULL inclusion
        String noReqsJson = objectMapper.writeValueAsString(noReqsCandidate);
        JsonNode noReqsNode = objectMapper.readTree(noReqsJson);
        assertTrue(noReqsNode.path("presentationFitValue").isMissingNode(),
                "null presentationFitValue must be omitted from JSON");
    }

    // =========================================================================
    // 4. Single Candidate Preserves Raw Fit Without Artificial 100% Collapse
    // =========================================================================

    @Test
    @DisplayName("B1 Requirement 13: Single candidate preserves genuine raw metrics without artificial 100% collapse")
    void singleCandidate_preservesRawMetrics_withoutArtificialCollapse() {
        RecommendedCandidateView single = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(200L)
                .displayName("Solo Dev")
                .presentationFitValue(0.65)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(35)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .build();

        assertEquals(0.65, single.presentationFitValue(), 1e-9, "Genuine raw fit 0.65 preserved, not 1.0 (HDEF-009)");
        assertEquals(35, single.storedWorkloadValue(), "Stored workload 35 preserved");
        assertEquals(MetricDataStatus.UNVERIFIED, single.workloadStatus());
        assertEquals(MetricDataStatus.DEFAULT, single.performanceStatus());
    }

    // =========================================================================
    // 5. Full Confirmation Wrapper Serialization (Sections 13 & 14)
    // =========================================================================

    @Test
    @DisplayName("B1 Requirements 13 & 14: ConfirmationRequiredDto -> RecommendAndAssignResult -> RecommendationView serialization does not leak internal fields")
    void fullConfirmationWrapper_serialization_preservesAllowlistAndActionContext() throws Exception {
        RecommendedCandidateView candidateView = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(42L)
                .displayName("Alice Engineer")
                .presentationFitValue(0.85)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(20)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        RecommendationView recommendationView = RecommendationView.builder()
                .projectId(10L)
                .requiredSkills(List.of("Java"))
                .candidates(List.of(candidateView))
                .differentiationStatus(RecommendationDifferentiationStatus.UNKNOWN)
                .presentationContractVersion(RecommendationView.PRESENTATION_CONTRACT_VERSION)
                .scoringModelVersion(RecommendationView.SCORING_MODEL_VERSION)
                .heuristicMode("BALANCED")
                .build();

        RecommendAndAssignResult preview = new RecommendAndAssignResult(
                false,
                76L,
                10L,
                42L,
                "Alice Engineer",
                "Best candidate for the task",
                recommendationView,
                null,
                "Ready to assign task 76 to Alice Engineer after confirmation."
        );

        ConfirmationRequiredDto confirmationDto = new ConfirmationRequiredDto(
                true,
                "act-12345",
                "recommendAndAssignTask",
                "Assign task 76 to Alice Engineer (42)",
                Map.of("taskId", 76L, "projectId", 10L, "selectedMemberId", 42L),
                preview,
                Instant.parse("2026-10-10T12:00:00Z")
        );

        String json = objectMapper.writeValueAsString(confirmationDto);
        JsonNode root = objectMapper.readTree(json);

        // Top level confirmation fields
        assertTrue(root.path("confirmationRequired").asBoolean());
        assertEquals("act-12345", root.path("actionId").asText());
        assertEquals("recommendAndAssignTask", root.path("toolName").asText());

        // Preview level
        JsonNode previewNode = root.path("preview");
        assertFalse(previewNode.path("assigned").asBoolean());
        assertEquals(76L, previewNode.path("taskId").asLong());
        assertEquals(10L, previewNode.path("projectId").asLong());
        assertEquals(42L, previewNode.path("selectedMemberId").asLong());
        assertEquals("Alice Engineer", previewNode.path("selectedMemberName").asText());

        // Recommendation level within preview
        JsonNode recNode = previewNode.path("recommendation");
        assertEquals("allowlisted-view-v1", recNode.path("presentationContractVersion").asText());
        assertEquals("relative-neutral-fixed-point-v2", recNode.path("scoringModelVersion").asText());
        assertEquals("UNKNOWN", recNode.path("differentiationStatus").asText());

        // Candidate node within preview
        JsonNode candNode = recNode.path("candidates").get(0);
        assertEquals(42L, candNode.path("candidateId").asLong());
        assertEquals("Alice Engineer", candNode.path("displayName").asText());
        assertEquals(0.85, candNode.path("presentationFitValue").asDouble(), 1e-9);
        assertEquals("MEASURED", candNode.path("fitStatus").asText());
        assertEquals(20, candNode.path("storedWorkloadValue").asInt());
        assertEquals("UNVERIFIED", candNode.path("workloadStatus").asText());
        assertEquals("DEFAULT", candNode.path("performanceStatus").asText());

        // Strict assertion: recursively check that no forbidden fields exist anywhere in the JSON
        assertForbiddenFieldMissingRecursively(root, "email");
        assertForbiddenFieldMissingRecursively(root, "confidenceScore");
        assertForbiddenFieldMissingRecursively(root, "confidence");
        assertForbiddenFieldMissingRecursively(root, "totalScore");
        assertForbiddenFieldMissingRecursively(root, "fitScore");
        assertForbiddenFieldMissingRecursively(root, "loadScore");
        assertForbiddenFieldMissingRecursively(root, "performanceScore");
        assertForbiddenFieldMissingRecursively(root, "rawPerformanceScore");
        assertForbiddenFieldMissingRecursively(root, "rawPerformance");
        assertForbiddenFieldMissingRecursively(root, "derivedPerformanceInput");
        assertForbiddenFieldMissingRecursively(root, "skillScore");
        assertForbiddenFieldMissingRecursively(root, "workloadScore");
        assertForbiddenFieldMissingRecursively(root, "rawWorkload");
        assertForbiddenFieldMissingRecursively(root, "rawFitScore");
        assertForbiddenFieldMissingRecursively(root, "rankingRawFit");
        assertForbiddenFieldMissingRecursively(root, "fullPrecisionScore");
        assertForbiddenFieldMissingRecursively(root, "rankingKey");
        assertForbiddenFieldMissingRecursively(root, "configuredWeights");
        assertForbiddenFieldMissingRecursively(root, "weights");
        assertForbiddenFieldMissingRecursively(root, "normalizedScores");
        assertFalse(json.contains("relative-explanation-safe-v2"));
    }

    @Test
    @DisplayName("B5 Canonical Cross-Repo Fixture: Independently constructed RecommendationView serializes identically to canonical fixture JsonNode")
    void canonicalCrossRepoFixture_independentConstructionMatchesFixtureJsonNode() throws Exception {
        RecommendedCandidateView c1 = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(10L)
                .displayName("Alice Engineer")
                .presentationFitValue(0.85)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(20)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        RecommendedCandidateView c2 = RecommendedCandidateView.builder()
                .rank(2)
                .candidateId(20L)
                .displayName("Bob Developer")
                .presentationFitValue(0.60)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(45)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        RecommendationView view = RecommendationView.builder()
                .projectId(100L)
                .requiredSkills(List.of("Java", "Spring Boot"))
                .heuristicMode("BALANCED")
                .differentiationStatus(RecommendationDifferentiationStatus.DIFFERENTIATED)
                .candidates(List.of(c1, c2))
                .aiExplanation("Alice Engineer is recommended at rank 1 with 85% skill fit (MEASURED). Stored workload value is unverified and performance is default.")
                .presentationContractVersion(RecommendationView.PRESENTATION_CONTRACT_VERSION)
                .scoringModelVersion(RecommendationView.SCORING_MODEL_VERSION)
                .build();

        // 1. Serialize independent DTO
        String serializedJson = objectMapper.writeValueAsString(view);
        JsonNode serializedNode = objectMapper.readTree(serializedJson);

        // 2. Read fixture file as JsonNode
        java.io.InputStream stream = getClass().getResourceAsStream("/contracts/phase1-recommendation-view.json");
        assertNotNull(stream, "Canonical fixture /contracts/phase1-recommendation-view.json must exist in test resources");
        JsonNode fixtureNode = objectMapper.readTree(stream);

        // 3. Assert serialized JsonNode equals fixture JsonNode
        assertEquals(fixtureNode, serializedNode, "Serialized RecommendationView must match canonical fixture JsonNode");

        // 4. Assert forbidden fields absent recursively and required versions present
        assertEquals("allowlisted-view-v1", serializedNode.path("presentationContractVersion").asText());
        assertEquals("relative-neutral-fixed-point-v2", serializedNode.path("scoringModelVersion").asText());
        assertEquals("DIFFERENTIATED", serializedNode.path("differentiationStatus").asText());

        assertForbiddenFieldMissingRecursively(serializedNode, "email");
        assertForbiddenFieldMissingRecursively(serializedNode, "totalScore");
        assertForbiddenFieldMissingRecursively(serializedNode, "roundedScore");
        assertForbiddenFieldMissingRecursively(serializedNode, "fullPrecisionScore");
        assertForbiddenFieldMissingRecursively(serializedNode, "rankingKey");
        assertForbiddenFieldMissingRecursively(serializedNode, "rankingRawFit");
        assertForbiddenFieldMissingRecursively(serializedNode, "fitScore");
        assertForbiddenFieldMissingRecursively(serializedNode, "loadScore");
        assertForbiddenFieldMissingRecursively(serializedNode, "performanceScore");
        assertForbiddenFieldMissingRecursively(serializedNode, "derivedPerformanceInput");
        assertForbiddenFieldMissingRecursively(serializedNode, "confidenceScore");
        assertForbiddenFieldMissingRecursively(serializedNode, "confidence");
        assertForbiddenFieldMissingRecursively(serializedNode, "skillScore");
        assertForbiddenFieldMissingRecursively(serializedNode, "workloadScore");
        assertForbiddenFieldMissingRecursively(serializedNode, "normalizedScores");
        assertForbiddenFieldMissingRecursively(serializedNode, "configuredWeights");
        assertForbiddenFieldMissingRecursively(serializedNode, "weights");
    }

    @Test
    @DisplayName("B5 Wrapper Test: ConfirmationRequiredDto retains action context and omits forbidden fields")
    void confirmationWrapperSerialization_retainsActionContext_andOmitsForbiddenFields() throws Exception {
        RecommendedCandidateView c1 = RecommendedCandidateView.builder()
                .rank(1)
                .candidateId(10L)
                .displayName("Alice Engineer")
                .presentationFitValue(0.85)
                .fitStatus(MetricDataStatus.MEASURED)
                .storedWorkloadValue(20)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .memberStatus("AVAILABLE")
                .build();

        RecommendationView recommendationView = RecommendationView.builder()
                .projectId(100L)
                .requiredSkills(List.of("Java", "Spring Boot"))
                .heuristicMode("BALANCED")
                .differentiationStatus(RecommendationDifferentiationStatus.DIFFERENTIATED)
                .candidates(List.of(c1))
                .aiExplanation("Preview explanation")
                .presentationContractVersion("allowlisted-view-v1")
                .scoringModelVersion("relative-neutral-fixed-point-v2")
                .build();

        RecommendAndAssignResult preview = new RecommendAndAssignResult(
                false, 500L, 100L, 10L, "Alice Engineer", "Selected top candidate",
                recommendationView, null, "Ready to assign"
        );

        Instant fixedExpiresAt = Instant.parse("2026-10-10T12:00:00Z");
        ConfirmationRequiredDto confirmation = new ConfirmationRequiredDto(
                true, "action-fixed-uuid-1234", "recommendAndAssignTask",
                "Assign task 500 to Alice Engineer",
                Map.of("taskId", 500L, "projectId", 100L, "candidateId", 10L),
                preview,
                fixedExpiresAt
        );

        JsonNode root = objectMapper.valueToTree(confirmation);
        assertEquals("action-fixed-uuid-1234", root.path("actionId").asText());
        assertEquals("recommendAndAssignTask", root.path("toolName").asText());
        assertEquals(10L, root.path("arguments").path("candidateId").asLong());
        assertEquals(500L, root.path("arguments").path("taskId").asLong());

        assertForbiddenFieldMissingRecursively(root, "email");
        assertForbiddenFieldMissingRecursively(root, "totalScore");
        assertForbiddenFieldMissingRecursively(root, "roundedScore");
        assertForbiddenFieldMissingRecursively(root, "fullPrecisionScore");
        assertForbiddenFieldMissingRecursively(root, "rankingKey");
        assertForbiddenFieldMissingRecursively(root, "rankingRawFit");
        assertForbiddenFieldMissingRecursively(root, "fitScore");
        assertForbiddenFieldMissingRecursively(root, "loadScore");
        assertForbiddenFieldMissingRecursively(root, "performanceScore");
        assertForbiddenFieldMissingRecursively(root, "derivedPerformanceInput");
        assertForbiddenFieldMissingRecursively(root, "confidenceScore");
        assertForbiddenFieldMissingRecursively(root, "confidence");
        assertForbiddenFieldMissingRecursively(root, "skillScore");
        assertForbiddenFieldMissingRecursively(root, "workloadScore");
        assertForbiddenFieldMissingRecursively(root, "normalizedScores");
        assertForbiddenFieldMissingRecursively(root, "configuredWeights");
        assertForbiddenFieldMissingRecursively(root, "weights");
    }



    private void assertForbiddenFieldMissingRecursively(JsonNode node, String fieldName) {
        if (node.isObject()) {
            assertFalse(node.has(fieldName), "FORBIDDEN field '" + fieldName + "' found in JSON: " + node);
            node.fields().forEachRemaining(entry -> assertForbiddenFieldMissingRecursively(entry.getValue(), fieldName));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertForbiddenFieldMissingRecursively(child, fieldName);
            }
        }
    }
}
