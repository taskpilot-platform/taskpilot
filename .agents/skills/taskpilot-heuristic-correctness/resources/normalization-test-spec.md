# Normalization & Heuristic Test Specification

This specification defines the 30 required test suites and scenarios for verifying TaskPilot's heuristic scoring engine in Phase 0 and Phase 1.
*Note: This specification defines tests only; no application tests are added or modified during Skill updates.*

---

## 1. Test Architecture & Anti-Gaming Rules

1. **Pure Unit Tests**: Tests must directly instantiate production classes (`ScoreRange`, `ScoreRanges`, `HeuristicStrategy`, `BalancedHeuristicStrategy`, `UrgentHeuristicStrategy`, `TrainingHeuristicStrategy`) without Spring context or Testcontainers.
2. **Never Mock Core Scoring Classes**: Do not mock `ScoreRange` in `ScoreRange` tests; do not mock `HeuristicStrategy` in strategy tests.
3. **Zero External Dependencies**: Tests must not connect to PostgreSQL, Docker, external LLMs, or network sockets.
4. **Fast & Deterministic**: The entire suite must execute in under 2 seconds.
5. **No Assertion Weakening**: Never inflate assertion tolerance `delta` or change numerical expectations to mask errors.
6. **No Sorting with Display-Rounded TotalScore**: Tests must verify that ordering relies on the internal fixed-point key ($10^9$ scale).
7. **No Lowering Internal Precision**: Do not alter or lower the $10^9$ scale to force tests to pass. Phase 0 must validate the 1e9 fixed-point scale before any production change. If Phase 0 disproves the scale, execution stops with `BLOCKED_CONTRACT_PENDING`. FIX MODE must not silently choose another scale.
8. **Immutable Numerical Oracles**: Do not alter or weaken oracles during FIX MODE to preserve the scale.
9. **No Serialization of Internal Ranking Objects**: View tests must verify that any object containing internal ranking state is not serialized directly into user-facing output.

---

## 2. 30 Required Test Groups (Phase 0 Specification)

1. **ScoreRange Benefit Direction**: Value 10 in [10, 90] with `BENCHMARK_BENEFIT` yields 0.0; value 90 yields 1.0.
2. **ScoreRange Cost Direction**: Value 10 in [10, 90] with `BENCHMARK_COST` yields 1.0; value 90 yields 0.0.
3. **Equal Range ($max \le min$)**: When all values are equal, verify neutral ranking contribution without artificial distortion and without weight redistribution.
4. **Single Candidate**: Exactly 1 candidate handled cleanly without arithmetic exceptions, preserving raw metrics without artificial 100% scores.
5. **Workload Monotonicity (N-001)**: With Fit and Performance equal, for candidates with `workload(A) = 10`, `workload(B) = 50`, `workload(C) = 90`, verify `score(A) >= score(B) >= score(C)` and `position(A) <= position(B) <= position(C)` across all three modes.
6. **Fit Monotonicity (N-005)**: With Workload and Performance equal, candidate with higher Fit ranks ahead of lower Fit (`score(A) >= score(B) >= score(C)` and `position(A) <= position(B) <= position(C)`).
7. **Performance Monotonicity (N-006)**: With Fit and Workload equal, candidate with higher synthetic measured performance ranks ahead (`position(A) <= position(B) <= position(C)`).
8. **Rounded-Comparator Regression (HDEF-006)**: Characterize the defect where two candidates with different unrounded scores (e.g. 0.844 vs 0.836) collapse into a tie under `round2()`.
9. **Fixed-Point Comparator at 1e9 (H-012)**: Verify that candidate ordering uses $\text{Math.round}(\text{fullPrecisionScore} \times 1\,000\,000\,000\text{L})$ to separate borderline candidates. Phase 0 must validate the 1e9 fixed-point scale before any production change. If Phase 0 disproves the scale, execution stops with `BLOCKED_CONTRACT_PENDING`. FIX MODE must not silently choose another scale. Numerical oracles must not be edited during FIX MODE to preserve the scale.
10. **Negative-Score Rounding Behavior**: Verify mathematical correctness when total score is negative due to workload subtraction.
11. **BALANCED Strategy with Explicit Weights**: Verify scoring using explicit test weights ($fit = 0.230, load = 0.648, perf = 0.122$).
12. **URGENT Strategy with Explicit Weights**: Verify scoring using explicit test weights ($fit = 0.474, load = 0.053, perf = 0.474$).
13. **TRAINING Strategy with Explicit Weights**: Verify scoring using explicit test weights ($fit = 0.188, load = 0.731, perf = 0.081$).
14. **Reversed Input Ordering (N-010)**: Verify that submitting candidates in reversed order yields identical final ranking.
15. **Raw Fit Tie-Break**: Verify that when fixed-point ranking keys are identical, candidates are ordered by raw Skill Fit descending.
16. **userId Final Tie-Break**: Verify that when ranking keys and raw Fit are identical, candidates are ordered deterministically by `userId` ascending.
17. **Full Tie Explanation State (H-013 amended)**: Verify that `INSUFFICIENT_TO_DIFFERENTIATE` applies when candidates cannot be meaningfully distinguished (raw Skill Fit values are equal, unavailable, or below $10^9$ quantization; unverified workload or default performance alone do not differentiate).
18. **Missing Member Skills (F3)**: Verify that an empty member skill profile results in data status `INSUFFICIENT_DATA`.
19. **Missing Task-Required Skills (F4, F5)**: Verify that empty/null task requirements yield data status `INSUFFICIENT_DATA` rather than claims of perfect match.
20. **Measured Zero Match (F2)**: Verify that zero matching skills among available skills yields data status `MEASURED` with value 0.0.
21. **Equal Default Performance (N-012)**: Verify that equal default `0.50` performance rows are displayed with status `DEFAULT` and wording `"Chưa đủ dữ liệu hiệu suất"`. Performance DEFAULT is proven from the application source contract; does not prove every deployed DB row is 0.5.
22. **UNVERIFIED Workload Wording (N-013)**: Verify that unverified workload is accompanied by approved wording `"Chưa có dữ liệu workload đáng tin cậy"`.
23. **Payload Allowlist (H-010, H-016)**: Verify that any object containing internal ranking state is not serialized directly into user-facing AI tool output. `CandidateScore` currently mixes internal ranking and presentation concerns; Phase 1 establishes an allowlisted presentation boundary.
24. **confidenceScore Omitted from View (H-019)**: Verify that `confidenceScore` is completely excluded from user-facing allowlisted view.
25. **scoringModelVersion and presentationContractVersion Present (H-014, H-015)**: Verify that new recommendation outputs contain `presentationContractVersion = "allowlisted-view-v1"` and `scoringModelVersion = "relative-rounded-v1"`. Both versions are mandatory in serialized recommendation contract, persisted tool-output logs, and future structured snapshots; prominent PM-facing display is optional.
26. **Invalid Workload Normalization Rejected (N-002)**: Verify that combining `BENCHMARK_COST` with subtractive scoring fails closed or throws validation exception.
27. **Missing heuristic.weights Behavior Characterized Separately**: Characterize the HTTP 500 `BusinessException` thrown when system settings are missing (source-proven in `references/current-runtime.md`).
28. **No Shared Database (N-007)**: Verify that tests execute purely in memory without reading or writing database records.
29. **No External LLM**: Verify that tests run without invoking external AI API endpoints.
30. **No Assignment Side Effect (N-007)**: Verify that scoring tests do not mutate task assignees or confirm pending actions.
