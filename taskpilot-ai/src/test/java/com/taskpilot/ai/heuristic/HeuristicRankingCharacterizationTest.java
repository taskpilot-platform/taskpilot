package com.taskpilot.ai.heuristic;

import com.taskpilot.ai.dto.CandidateScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Characterization Tests: Ranking Comparator Defect and Input-Order Instability
 *
 * Covers:
 * - Item 4: Current rounded-score comparator defect (HDEF-006)
 * - Item 7: Input-order instability under tied rounded scores (HDEF-006)
 * - Proposed 1e9 comparator resolution (Oracles N-008, N-009, N-010)
 *
 * Rules:
 * - Pure unit tests (no Spring context, no live DB).
 * - Comparator logic directly instantiated and compared.
 */
class HeuristicRankingCharacterizationTest {

    // Helper implementing current production rounding: round2(v) = Math.round(v * 100.0) / 100.0
    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // =========================================================================
    // 4. Current rounded-score comparator defect (HDEF-006)
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: current comparator sorts by rounded totalScore, collapsing borderline candidates into tie (HDEF-006)")
    void currentBehavior_roundedScoreComparator_collapsesBorderlineCandidatesTieDefect() {
        // Candidate A has full-precision score 0.844
        // Candidate B has full-precision score 0.836
        double fullScoreA = 0.844;
        double fullScoreB = 0.836;

        // Current production rounds before storing on CandidateScore (AutoAssignmentService.java:249):
        double roundedScoreA = round2(fullScoreA); // 0.84
        double roundedScoreB = round2(fullScoreB); // 0.84

        assertEquals(0.84, roundedScoreA, 1e-9);
        assertEquals(0.84, roundedScoreB, 1e-9);

        CandidateScore candidateA = CandidateScore.builder()
                .userId(101L)
                .fullName("Alice Candidate")
                .totalScore(roundedScoreA)
                .build();

        CandidateScore candidateB = CandidateScore.builder()
                .userId(102L)
                .fullName("Bob Candidate")
                .totalScore(roundedScoreB)
                .build();

        // Current comparator in AutoAssignmentService.java:206:
        // Comparator.comparingDouble(CandidateScore::getTotalScore).reversed()
        Comparator<CandidateScore> currentComparator =
                Comparator.comparingDouble(CandidateScore::getTotalScore).reversed();

        int comparison = currentComparator.compare(candidateA, candidateB);

        // Characterizes defect: comparator returns 0 (tied!) even though fullScoreA > fullScoreB
        assertEquals(0, comparison,
                "Current production comparator treats 0.844 and 0.836 as identical ties due to 2-decimal display rounding");
    }

    // =========================================================================
    // Pure Raw-to-Ranking Pipeline Characterization
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: raw-to-ranking pipeline reproduces borderline score collapse and input-order instability (HDEF-006)")
    void currentBehavior_rawToRankingPipeline_collapsesBorderlineScoresAndShowsInstability() {
        // Synthetic design-labeled fixture (MIXED_FIXTURE, not runtime configuration)
        HeuristicWeights mixedFixture = new HeuristicWeights(0.230, 0.648, 0.122);
        HeuristicNormalizationConfig normConfig = new HeuristicNormalizationConfig(
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT,
                HeuristicNormalization.BENCHMARK_BENEFIT
        );
        BalancedHeuristicStrategy strategy = new BalancedHeuristicStrategy(new HeuristicConfig(mixedFixture, normConfig));

        // 3 Candidates with raw metrics:
        // Candidate A (id=1): Fit = 0.85, Load = 0.20, Perf = 0.50 (equal-range criterion on Performance!)
        // Candidate B (id=2): Fit = 0.84, Load = 0.20, Perf = 0.50
        // Candidate C (id=3): Fit = 0.50, Load = 0.80, Perf = 0.50
        RawScores rawA = new RawScores(0.85, 0.20, 0.50);
        RawScores rawB = new RawScores(0.84, 0.20, 0.50);
        RawScores rawC = new RawScores(0.50, 0.80, 0.50);

        List<RawScores> rawList = List.of(rawA, rawB, rawC);

        // Step 1: Real ScoreRanges.from(...)
        ScoreRanges ranges = ScoreRanges.from(rawList);
        assertEquals(0.50, ranges.fit().min(), 1e-9);
        assertEquals(0.85, ranges.fit().max(), 1e-9);
        assertEquals(0.20, ranges.load().min(), 1e-9);
        assertEquals(0.80, ranges.load().max(), 1e-9);
        assertEquals(0.50, ranges.performance().min(), 1e-9);
        assertEquals(0.50, ranges.performance().max(), 1e-9); // equal range!

        // Step 2: Real strategy.normalize(...)
        NormalizedScores normA = strategy.normalize(rawA, ranges);
        NormalizedScores normB = strategy.normalize(rawB, ranges);
        NormalizedScores normC = strategy.normalize(rawC, ranges);

        // Equal-range Performance returns 1.0 (HDEF-001) for all 3 candidates
        assertEquals(1.0, normA.performance(), 1e-9);
        assertEquals(1.0, normB.performance(), 1e-9);
        assertEquals(1.0, normC.performance(), 1e-9);

        // Intermediate Min-Max normalization for Fit:
        // Candidate A: (0.85 - 0.50) / 0.35 = 1.0
        // Candidate B: (0.84 - 0.50) / 0.35 = 0.97142857...
        assertEquals(1.0, normA.fit(), 1e-9);
        assertEquals(0.34 / 0.35, normB.fit(), 1e-9);

        // Step 3: Real strategy.score(...)
        double fullScoreA = strategy.score(normA); // 0.230*1.0 - 0.648*0.0 + 0.122*1.0 = 0.352
        double fullScoreB = strategy.score(normB); // 0.230*(0.34/0.35) - 0.0 + 0.122 = 0.34542857...
        double fullScoreC = strategy.score(normC); // -0.526

        assertEquals(0.352, fullScoreA, 1e-6);
        assertEquals(0.34542857, fullScoreB, 1e-6);
        assertTrue(fullScoreA > fullScoreB, "Full-precision score A is strictly greater than score B");

        // Step 4: Current two-decimal round2 behavior (reproducing AutoAssignmentService.java:249)
        double roundedScoreA = round2(fullScoreA); // 0.35
        double roundedScoreB = round2(fullScoreB); // 0.35
        double roundedScoreC = round2(fullScoreC); // -0.53

        assertEquals(0.35, roundedScoreA, 1e-9);
        assertEquals(0.35, roundedScoreB, 1e-9);

        // Step 5: CandidateScore construction
        CandidateScore candA = CandidateScore.builder().userId(1L).fullName("Alice").totalScore(roundedScoreA).build();
        CandidateScore candB = CandidateScore.builder().userId(2L).fullName("Bob").totalScore(roundedScoreB).build();
        CandidateScore candC = CandidateScore.builder().userId(3L).fullName("Charlie").totalScore(roundedScoreC).build();

        // Step 6: Current production comparator
        Comparator<CandidateScore> currentComparator =
                Comparator.comparingDouble(CandidateScore::getTotalScore).reversed();

        // Step 7: Final ordering under different input sequences (demonstrating stability/instability)
        List<CandidateScore> order1 = new ArrayList<>(List.of(candA, candB, candC));
        order1.sort(currentComparator);

        List<CandidateScore> order2 = new ArrayList<>(List.of(candB, candA, candC));
        order2.sort(currentComparator);

        // Characterizes defect: order1 recommends Alice (1L), order2 recommends Bob (2L)
        assertEquals(1L, order1.get(0).getUserId());
        assertEquals(2L, order2.get(0).getUserId());
        assertNotEquals(order1.get(0).getUserId(), order2.get(0).getUserId(),
                "Current raw-to-ranking pipeline collapses borderline distinct scores into tie, yielding input-order dependent winner");
    }

    // =========================================================================
    // 7. Input-order instability under tied rounded scores (HDEF-006)
    // =========================================================================

    @Test
    @DisplayName("current-behavior characterization: input order dictates winner under tied rounded scores due to stable sort (HDEF-006)")
    void currentBehavior_tiedRoundedScores_inputOrderInstabilityDefect() {
        // Two candidates with identical rounded totalScore (0.84)
        CandidateScore candidate1 = CandidateScore.builder()
                .userId(101L)
                .fullName("Candidate 101")
                .totalScore(0.84)
                .build();

        CandidateScore candidate2 = CandidateScore.builder()
                .userId(102L)
                .fullName("Candidate 102")
                .totalScore(0.84)
                .build();

        Comparator<CandidateScore> currentComparator =
                Comparator.comparingDouble(CandidateScore::getTotalScore).reversed();

        // Scenario 1: Input list [candidate1, candidate2]
        List<CandidateScore> list1 = new ArrayList<>(List.of(candidate1, candidate2));
        list1.sort(currentComparator);
        assertEquals(101L, list1.get(0).getUserId(),
                "Candidate 101 placed first because it appeared first in input list");

        // Scenario 2: Reversed input list [candidate2, candidate1]
        List<CandidateScore> list2 = new ArrayList<>(List.of(candidate2, candidate1));
        list2.sort(currentComparator);
        assertEquals(102L, list2.get(0).getUserId(),
                "Candidate 102 placed first because it appeared first in input list");

        // Characterizes defect HDEF-006 symptom: top candidate changes based strictly on input collection order
        assertNotEquals(list1.get(0).getUserId(), list2.get(0).getUserId(),
                "Current sorting is unstable: top recommended candidate flips based solely on input order!");
    }

    // =========================================================================
    // Validation: Proposed Fixed-Point 1e9 Multi-Tier Comparator Resolves Defects (Oracle N-009)
    // =========================================================================

    private record ProposedCandidate(
            long userId,
            double fullScore,
            double rawSkillFit,
            long rankingKey
    ) {
        static ProposedCandidate of(long userId, double fullScore, double rawSkillFit) {
            long key = Math.round(fullScore * 1_000_000_000L);
            return new ProposedCandidate(userId, fullScore, rawSkillFit, key);
        }
    }

    private static final Comparator<ProposedCandidate> PROPOSED_COMPARATOR = Comparator
            .<ProposedCandidate>comparingLong(ProposedCandidate::rankingKey).reversed()
            .thenComparing(Comparator.comparingDouble(ProposedCandidate::rawSkillFit).reversed())
            .thenComparingLong(ProposedCandidate::userId);

    @Test
    @DisplayName("future-contract validation: Oracle N-009 Case A - when current round2 scores differ, future 1e9 keys preserve the same relative order")
    void validation_oracleN009_caseA_differingRound2ScoresPreserveOrder() {
        // Candidate High (fullScore = 0.852 -> round2 = 0.85)
        // Candidate Low  (fullScore = 0.345 -> round2 = 0.35)
        ProposedCandidate candHigh = ProposedCandidate.of(101L, 0.852, 0.85);
        ProposedCandidate candLow = ProposedCandidate.of(102L, 0.345, 0.50);

        CandidateScore currentHigh = CandidateScore.builder().userId(101L).totalScore(round2(0.852)).build();
        CandidateScore currentLow = CandidateScore.builder().userId(102L).totalScore(round2(0.345)).build();

        Comparator<CandidateScore> currentComparator =
                Comparator.comparingDouble(CandidateScore::getTotalScore).reversed();

        // Current comparator orders High before Low
        assertTrue(currentComparator.compare(currentHigh, currentLow) < 0,
                "Current comparator orders High before Low (0.85 vs 0.35)");

        // Future proposed comparator strictly preserves the same relative order
        assertTrue(PROPOSED_COMPARATOR.compare(candHigh, candLow) < 0,
                "Future 1e9 comparator preserves the identical relative order (852_000_000L vs 345_000_000L)");
    }

    @Test
    @DisplayName("future-contract validation: Oracle N-009 Case B - when current round2 scores tie, future 1e9 keys distinguish candidates (N-008, N-009)")
    void validation_oracleN009_caseB_tiedRound2ScoresDistinguishedByFixedPoint() {
        // Candidates with borderline full scores (0.844 vs 0.836) collapsing to 0.84
        ProposedCandidate candA = ProposedCandidate.of(101L, 0.844, 0.85);
        ProposedCandidate candB = ProposedCandidate.of(102L, 0.836, 0.85);

        // Cand A strictly precedes Cand B under 1e9 comparator
        int comp = PROPOSED_COMPARATOR.compare(candA, candB);
        assertTrue(comp < 0, "Candidate A (0.844) must strictly precede Candidate B (0.836)");

        // Input order independence (Oracle N-010):
        List<ProposedCandidate> order1 = new ArrayList<>(List.of(candA, candB));
        order1.sort(PROPOSED_COMPARATOR);

        List<ProposedCandidate> order2 = new ArrayList<>(List.of(candB, candA));
        order2.sort(PROPOSED_COMPARATOR);

        assertEquals(order1.get(0).userId(), order2.get(0).userId(), "Winner is identical regardless of input order");
        assertEquals(101L, order1.get(0).userId());
    }

    @Test
    @DisplayName("future-contract validation: Oracle N-009 Case C - when full scores map to equal 1e9 keys, raw Fit and userId tie-break (N-010, N-011)")
    void validation_oracleN009_caseC_fullTieResolvedByRawFitAndUserId() {
        // Tier 2: Identical rankingKey, different raw Skill Fit
        ProposedCandidate tieKeyHighFit = ProposedCandidate.of(201L, 0.850, 0.90);
        ProposedCandidate tieKeyLowFit = ProposedCandidate.of(202L, 0.850, 0.70);

        List<ProposedCandidate> listTieKey = new ArrayList<>(List.of(tieKeyLowFit, tieKeyHighFit));
        listTieKey.sort(PROPOSED_COMPARATOR);

        assertEquals(201L, listTieKey.get(0).userId(),
                "When ranking keys are tied, higher raw Skill Fit wins (Tier 2)");

        // Tier 3: Identical rankingKey, identical raw Skill Fit -> deterministic userId ascending
        ProposedCandidate fullTieUser5 = ProposedCandidate.of(5L, 0.850, 0.90);
        ProposedCandidate fullTieUser9 = ProposedCandidate.of(9L, 0.850, 0.90);

        List<ProposedCandidate> listFullTie = new ArrayList<>(List.of(fullTieUser9, fullTieUser5));
        listFullTie.sort(PROPOSED_COMPARATOR);

        assertEquals(5L, listFullTie.get(0).userId(),
                "When ranking keys and raw Fit are identical, lower userId wins deterministically (Tier 3)");
    }
}
