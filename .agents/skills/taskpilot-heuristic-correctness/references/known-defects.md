# Known Defects Register

This document catalogues verified defects, structural hazards, and technical debt in TaskPilot's heuristic scoring engine.
Every defect includes its status, source evidence, Phase 1 relevance, and future-phase relevance.

---

## HDEF-001: Equal-Range Normalization Returns 1.0
- **Status**: OPEN (Target for Phase 1 fix under H-004)
- **Source Evidence**: `ScoreRange.java:6-8`
  ```java
  if (max <= min) {
      return 1.0;
  }
  ```
- **Mechanism & Symptom**:
  - When all candidates have the same metric value ($max \le min$, e.g. all have `workload = 0`), `ScoreRange.normalize` returns `1.0`.
  - In `HeuristicStrategy.score()`, every candidate is penalized by $-w_{load} \times 1.0$.
  - In `CandidateScore`, `loadScore` becomes `1.0` (100% load), and `workloadScore` becomes `0.0`.
  - The LLM explanation receives `Load: 100%` and hallucinates that candidates are overwhelmed despite zero workload.
  - The total score drops into negative values (e.g., `-0.30`).
- **Phase 1 Relevance**: IN SCOPE. Phase 1 replaces this behavior with a neutral ranking contribution without weight redistribution (H-004) and decouples ranking from raw explanations (H-010).
- **Future-Phase Relevance**: Ensures robust baseline behavior under any future candidate-set scoring model.

---

## HDEF-002: Unsafe Workload Double Inversion
- **Status**: OPEN (Governed by H-003 invariant)
- **Source Evidence**: `HeuristicStrategy.java:18-20`, `HeuristicNormalization.java:7`, `HeuristicConfigProvider.java:184-210`
- **Mechanism & Symptom**:
  - `HeuristicStrategy.score()` hardcodes subtraction: $\text{Score} = w_{fit} \cdot F - w_{load} \cdot L + w_{perf} \cdot P$.
  - If `system_settings.heuristic.normalization` is configured with `"load": "BENCHMARK_COST"`, smaller workload maps to $1.0$ and larger workload maps to $0.0$.
  - Subtraction then penalizes the idle member by $-w_{load} \times 1.0$ while penalizing the overloaded member by $0.0$.
  - **The overloaded member ranks first and the idle member ranks last.**
- **Phase 1 Relevance**: IN SCOPE. Structural validation invariant (H-003) must fail closed or reject invalid configuration.
- **Future-Phase Relevance**: Prevents catastrophic ranking inversion if admin UI or automated tuning is introduced.

---

## HDEF-003: Ambiguous Score Contract & Cross-Run Incomparability
- **Status**: OPEN (Addressed by H-010 and H-012)
- **Source Evidence**: `AutoAssignmentService.java:234-245`, `CandidateScore.java:25`
- **Mechanism & Symptom**:
  - Candidate-set relative Min-Max normalization creates scores that are relative to the specific candidate pool.
  - The UI and LLM treat `totalScore` as if it were an absolute suitability score or match percentage.
  - Cross-run comparisons are mathematically invalid.
- **Phase 1 Relevance**: IN SCOPE. Phase 1 clearly separates internal ranking metrics from user-facing presentation (H-010, H-015).
- **Future-Phase Relevance**: Evaluates absolute or all-benefit scoring in future phases.

---

## HDEF-004: Double Normalization of Bounded Metrics
- **Status**: OPEN (Acknowledged transitional debt under H-001)
- **Source Evidence**: `AutoAssignmentService.calculateFitScore` (`AutoAssignmentService.java:256-277`), `ScoreRanges.from` (`ScoreRanges.java:14-22`)
- **Mechanism & Symptom**:
  - Skill Fit is calculated into $[0.0, 1.0]$. Passing it through candidate-set Min-Max expands tiny differences (e.g. 0.84 vs 0.85) into extreme ends (0.0 vs 1.0).
- **Phase 1 Relevance**: TRANSITIONAL INVARIANT. Preserved for Phase 1 to minimize behavioral divergence (H-001), but separated from raw explanation (H-010).
- **Future-Phase Relevance**: Candidate for transition to absolute bounded scoring in a future release.

---

## HDEF-005: Data Foundation Blockers
- **Status**: DEFERRED (Governed by H-006)
- **Source Evidence**: `users.current_workload` (untracked updates), `project_members.performance_score` (static 0.50), lack of `tasks.completed_at`.
- **Phase 1 Relevance**: OUT OF SCOPE. Phase 1 must not modify database migrations, add background triggers, or create evaluation models (H-006).
- **Future-Phase Relevance**: Prerequisite for genuine Delivery Reliability and Adaptive Preference Learning.

---

## HDEF-006: Ranking Comparator Uses Two-Decimal Rounded Score
- **Status**: OPEN (Target for Phase 1 fix under H-012)
- **Source Evidence**: `AutoAssignmentService.java:203, 239`:
  ```java
  .sorted(Comparator.comparingDouble(CandidateScore::getTotalScore).reversed())
  ...
  .totalScore(round2(totalScore))
  ```
- **Mechanism & Symptom**:
  - Candidates are sorted using `CandidateScore.getTotalScore()`, which has already been rounded to two decimal places via `round2()`.
  - Candidates with distinct full-precision scores (e.g. `0.844` vs `0.836`) may round to `0.84`, collapsing into an arbitrary tie resolved by retrieval order.
- **Phase 1 Relevance**: IN SCOPE. Phase 1 enforces full-precision internal ranking key with $10^9$ fixed-point quantization and explicit deterministic tie-breakers (H-012).
- **Future-Phase Relevance**: Guarantees deterministic, reproducible candidate ranking across all environments.

---

## HDEF-007: Internal CandidateScore Leaks Through Nested Generic JSON Rendering
- **Status**: OPEN (Target for Phase 1 fix under H-010 and H-016)
- **Source Evidence**: `aiChatHelpers.ts:328-335`, `StreamingToolCoordinator.java:132-138`, `ToolEventCard.tsx:32-34`
- **Mechanism & Symptom**:
  - `recommendAndAssignTask` returns `ConfirmationRequiredDto` with `preview` containing `AutoAssignmentResponse` and full `CandidateScore` objects.
  - Frontend `formatFriendlyToolPayload` applies `ID_KEY_PATTERN = /^id$|id$/i` only to top-level keys.
  - The nested `preview` object is stringified via `JSON.stringify(v)`.
  - All internal fields (`userId`, `email`, `fitScore`, `loadScore`, `performanceScore`, `confidenceScore`, `totalScore`, `workloadScore`) leak directly into rendered markdown.
- **Phase 1 Relevance**: IN SCOPE. Any object containing internal ranking state must not be serialized directly into a user-facing payload. Phase 1 enforces an explicit user-facing allowlist view and prevents internal ranking leakage (H-010, H-016).
- **Future-Phase Relevance**: Foundational security and contract boundary for all AI tool outputs.

---

## HDEF-008: Performance Default Value Displayed as 100% After Equal-Range Normalization
- **Status**: OPEN (Target for Phase 1 fix under H-004, H-010, H-011)
- **Source Evidence**: `ScoreRange.java:7`, `AutoAssignmentService.java:242`
- **Mechanism & Symptom**:
  - All members in a project typically share the default `performance_score = 0.50`.
  - Equal range triggers $max \le min$, causing `ScoreRange.normalize` to return `1.0`.
  - The candidate's normalized performance is reported as `1.0` (100%), and the LLM hallucinates "top historical performance" for unmeasured default members.
- **Phase 1 Relevance**: IN SCOPE. Equal range must contribute neutrally to ranking, raw Performance must be reported with status `DEFAULT`, and UI must display `"Chưa đủ dữ liệu hiệu suất"`. Performance DEFAULT is proven from the application source contract; does not prove every deployed DB row is 0.5 (H-004, H-011, H-015).
- **Future-Phase Relevance**: Prevents false confidence claims as member evaluations are developed.

---

## HDEF-009: Single Candidate Displayed as Fit/Load/Performance 100%
- **Status**: OPEN (Target for Phase 1 fix under H-004, H-010)
- **Source Evidence**: `ScoreRange.java:7`, `AutoAssignmentService.java:188-204`
- **Mechanism & Symptom**:
  - When only 1 eligible candidate exists, all metric ranges have $max = min$.
  - All normalized criteria become `1.0`.
  - UI renders Fit: 100%, Load: 100%, Performance: 100%, confusing the PM.
- **Phase 1 Relevance**: IN SCOPE. Neutral equal-range handling ensures single candidate is rendered with raw metrics and appropriate data statuses (H-004, H-010, H-011).
- **Future-Phase Relevance**: Consistent single-candidate handling across all future scoring engines.

---

## HDEF-010: confidenceScore Reflects Membership Count Rather than Evidence Confidence
- **Status**: OPEN (Addressed in Phase 1 by H-019)
- **Source Evidence**: `AutoAssignmentService.java:306-313`, `ProjectMemberRepository.java:51-56`
- **Mechanism & Symptom**:
  - `resolveConfidence` returns `0.0, 0.4, 0.7, 1.0` based solely on the count of project memberships the user belongs to.
  - Default `0.50` performance records increment this count.
  - Has zero correlation with task completion reliability or sample variance.
- **Phase 1 Relevance**: IN SCOPE. `confidenceScore` must be omitted from user-facing allowlisted views in Phase 1 (H-019).
- **Future-Phase Relevance**: Genuine statistical confidence model deferred until task completion ground truth is available.

---

## HDEF-011: Full Tie is Presented as a Meaningful Top Recommendation
- **Status**: OPEN (Target for Phase 1 fix under H-013)
- **Source Evidence**: `AutoAssignmentService.java:203`, `AutoAssignmentService.java:413-429`
- **Mechanism & Symptom**:
  - When all candidates have identical metrics and Fit scores, the comparator breaks ties by arbitrary order (or `userId`).
  - The system presents candidate #1 as the "recommended candidate" with no indication that candidates are indistinguishable.
- **Phase 1 Relevance**: IN SCOPE. Output must mark the state as `INSUFFICIENT_TO_DIFFERENTIATE` when internal ranking keys, raw Skill Fit, and other evidence are identical, and avoid claiming technical tie-break as superiority (H-013, H-015).
- **Future-Phase Relevance**: Builds trust with PMs by transparently acknowledging data equivalence.

---

## HDEF-012: Effective Runtime Weights are DB-Only and Unseeded in Repository Source
- **Status**: OPEN (Documented finding, SOURCE_PROVEN)
- **Source Evidence**: `HeuristicConfigProvider.java:76-78`, `V1__init_taskpilot_schema.sql:204`
- **Mechanism & Symptom**:
  - System setting `heuristic.weights` has no Flyway seed and no Java fallback.
  - A clean database deployment will throw HTTP 500 on recommendation calls unless an external setup script runs.
- **Phase 1 Relevance**: INFORMATIONAL / PHASE 0 TEST ISOLATION. Phase 0 unit tests must inject explicit test weights and never depend on runtime DB state (`references/current-runtime.md#15-runtime-weight-source-source_proven`).
- **Future-Phase Relevance**: Requires a dedicated bootstrap migration or application config fallback in a separate configuration task.

---

## HDEF-013: AI Feedback PATCH Endpoint Lacks Ownership Validation
- **Status**: OPEN
- **Classification**: `SEPARATE_SECURITY_TASK` (NOT part of Phase 1 heuristic scoring fix)
- **Source Evidence**: `AiFeedbackController.java` / `AiFeedbackService.java`
- **Mechanism & Symptom**:
  - Updating AI interaction feedback does not strictly verify requester ownership against the initiating session or project.
- **Phase 1 Relevance**: EXCLUDED FROM PHASE 1. Must not be mixed with heuristic scoring fixes.
- **Future-Phase Relevance**: Target for an isolated application-security task.
