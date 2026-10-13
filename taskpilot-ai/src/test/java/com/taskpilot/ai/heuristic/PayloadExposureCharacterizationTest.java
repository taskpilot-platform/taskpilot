package com.taskpilot.ai.heuristic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Characterization Tests: Payload Exposure and Boundary Leakage
 *
 * Covers Item 10:
 * - Characterizes what CandidateScore and AutoAssignmentResponse expose
 * - Inspects JSON serialization of internal ranking and presentation fields
 * - Validates evidence for decisions H-010 (Output Boundary), H-016 (Payload Allowlist), and H-019 (confidenceScore removal)
 *
 * Rules:
 * - Pure unit test using Jackson ObjectMapper and reflection.
 * - Zero production modifications.
 */
class PayloadExposureCharacterizationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("current-behavior characterization: CandidateScore contains internal ranking state, confidence scores, and PII fields")
    void currentBehavior_candidateScore_containsInternalAndPiiFields() {
        Set<String> fieldNames = Arrays.stream(CandidateScore.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        // Internal ranking fields exposed directly on DTO
        assertTrue(fieldNames.contains("totalScore"), "CandidateScore exposes relative totalScore");
        assertTrue(fieldNames.contains("fitScore"), "CandidateScore exposes normalized fitScore");
        assertTrue(fieldNames.contains("loadScore"), "CandidateScore exposes normalized loadScore");
        assertTrue(fieldNames.contains("performanceScore"), "CandidateScore exposes normalized performanceScore");
        assertTrue(fieldNames.contains("skillScore"), "CandidateScore exposes duplicate skillScore");
        assertTrue(fieldNames.contains("workloadScore"), "CandidateScore exposes inverted workloadScore");
        assertTrue(fieldNames.contains("heuristicMode"), "CandidateScore exposes heuristicMode");

        // PII and technical identity exposed
        assertTrue(fieldNames.contains("email"), "CandidateScore exposes user email (PII)");
        assertTrue(fieldNames.contains("userId"), "CandidateScore exposes internal database userId");

        // Unproven confidenceScore exposed
        assertTrue(fieldNames.contains("confidenceScore"), "CandidateScore exposes unproven confidenceScore (H-019)");
    }

    @Test
    @DisplayName("current-behavior characterization: JSON serialization leaks internal ranking fields and confidenceScore into tool outputs")
    void currentBehavior_jsonSerialization_leaksInternalRankingFields() throws Exception {
        CandidateScore candidate = CandidateScore.builder()
                .userId(42L)
                .fullName("Alice Engineer")
                .email("alice@taskpilot.internal")
                .fitScore(0.85)
                .loadScore(0.20)
                .performanceScore(0.50)
                .confidenceScore(0.70)
                .skillScore(0.85)
                .workloadScore(0.80)
                .totalScore(0.68)
                .currentWorkload(2)
                .status("AVAILABLE")
                .heuristicMode("BALANCED")
                .build();

        AutoAssignmentResponse response = AutoAssignmentResponse.builder()
                .projectId(10L)
                .requiredSkills(List.of("Java"))
                .candidates(List.of(candidate))
                .aiExplanation("Alice is recommended.")
                .build();

        String json = objectMapper.writeValueAsString(response);
        JsonNode root = objectMapper.readTree(json);

        JsonNode candidateNode = root.path("candidates").get(0);

        // Characterizes leakage of internal fields to LLM / UI
        assertFalse(candidateNode.path("email").isMissingNode(), "email is leaked in serialized JSON");
        assertFalse(candidateNode.path("confidenceScore").isMissingNode(), "confidenceScore is leaked in serialized JSON");
        assertFalse(candidateNode.path("totalScore").isMissingNode(), "totalScore is leaked in serialized JSON");
        assertFalse(candidateNode.path("loadScore").isMissingNode(), "loadScore is leaked in serialized JSON");

        // Characterizes lack of Phase 1 metadata
        assertTrue(root.path("scoringModelVersion").isMissingNode(),
                "Current production response lacks mandatory scoringModelVersion (H-014, H-015)");
    }
}
