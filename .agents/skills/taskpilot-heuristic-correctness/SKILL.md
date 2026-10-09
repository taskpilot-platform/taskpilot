---
name: taskpilot-heuristic-correctness
description: Comprehensive workflow guide, invariants, decision gates, and verification protocol for safely auditing and correcting TaskPilot's AHP-inspired heuristic task assignment scoring mechanism across Phase 0 and Phase 1.
---

# TaskPilot Heuristic Correctness & Adaptive Assignment Skill

## 1. Overview & Purpose

TaskPilot uses an AHP-inspired weighted scoring mechanism for candidate recommendation in task assignment.
This Skill protects mathematical correctness, business invariants, and execution safety across:
- **Skill Fit ($F$)**: Positive suitability metric based on skill coverage and proficiency levels.
- **Workload ($L$)**: Workload cost metric that penalizes heavily loaded team members.
- **Performance ($P$)**: Historical delivery reliability signal with temporal decay.
- **Normalization Engine**: Bounded score ranges, Min-Max mapping, and equal-range edge cases.
- **Mode-Specific Weights**: Strategy pattern implementations for `BALANCED`, `URGENT`, and `TRAINING`.
- **Candidate Presentation Semantics**: Separation between internal ranking contributions, LLM prompt formatting, and user-facing UI.
- **AI Tool Integration**: LangChain4j tools, human-in-the-loop pending actions, and safe payload exposure.

### Distinct Capabilities Disambiguation
The Skill enforces a strict distinction between concepts and forbids confusing them:
- **Offline AHP Weight Derivation**: Academic pairwise-comparison matrices ($CR < 10\%$) used solely to determine initial strategic weights.
- **Runtime Weighted Scoring**: Deterministic multi-criteria scoring executed per assignment recommendation.
- **Fixed Strategy Selection**: Contextual selection between predefined modes (`BALANCED`, `URGENT`, `TRAINING`).
- **Runtime Configuration**: Dynamic loading of weights and normalization rules from `system_settings`.
- **Temporal Adaptation**: Recent performance observation weighting ($0.5, 0.3, 0.2$).
- **Reliability Blending**: Cold-start confidence blending with project prior.
- **PM Preference Learning**: Future supervised learning from PM accept/override decisions (*not implemented*).
- **Automatic Weight Mutation**: Automated database weight mutation (*strictly rejected under H-005*).

*Rule*: The Skill must never claim that fixed modes or manually configurable weights represent learned adaptive behavior.

---

## 2. Decision Status Model

Technical and business decisions are governed by strict lifecycle statuses in [`references/decisions.md`](references/decisions.md):

| Status | Meaning |
| :--- | :--- |
| `PROPOSED` | Technical proposal not yet approved. |
| `PENDING_BUSINESS_CONFIRMATION` | Business-facing behavior awaiting stakeholder confirmation. |
| `APPROVED_FOR_PHASE_1` | Approved only for the current transitional release scope (Phase 1). |
| `APPROVED_TECHNICAL_INVARIANT` | Correctness or safety invariant that must always hold across all phases. |
| `DEFERRED` | Valid concept explicitly postponed to a future phase. |
| `REJECTED` | The option must not be implemented under the current contract. |
| `IMPLEMENTED` | Production code implements the accepted decision. |
| `VERIFIED` | Implementation passed all defined verification gates. |
| `SUPERSEDED` | A later accepted decision replaces it. |

*Rule*: Do not use `ACCEPTED` without a qualifier when the scope is transitional.
*Rule*: Findings of fact are preserved in `references/current-runtime.md` with source-proven labels; they are not decisions and must not use decision approval statuses.

---

## 3. Phase 0 & Phase 1 Execution Workflow

All execution follows a strict phase-gated sequence:

```text
READ ACCEPTED DECISIONS
  │
  ▼
VERIFY CLEAN BASELINE
  │
  ▼
PHASE 0 CHARACTERIZATION TESTS (explicit weights, reproduce defects, in-memory)
  │
  ▼
TEST RED (focused pure unit tests)
  │
  ▼
B1: BACKEND OUTPUT CONTRACT (allowlisted DTO, scoringModelVersion)
  │
  ▼
B2: LLM EXPLANATION CONTRACT (status-aware, unverified/default labeling)
  │
  ▼
B3: FRONTEND VIEW CONTRACT (consume allowlist view, prevent JSON leakage)
  │
  ▼
A: SCORING CORRECTION (neutral equal-range, fixed-point 1e9 comparator, tie-break)
  │
  ▼
BACKEND VERIFICATION (focused & regression tests offline)
  │
  ▼
FRONTEND VERIFICATION (rendering tests clean)
  │
  ▼
CROSS-REPO PAYLOAD VERIFICATION (contract matching between repos)
  │
  ▼
DIFF AUDIT (verify-diff.ps1 scope & whitespace audit)
  │
  ▼
STOP OR REACT (stop if verified; enter bounded iteration if failed)
```

### Hard Safety Rule
**Do not deploy or mark Phase 1 `PHASE_1_VERIFIED` if scoring correction is present but the user-facing view still consumes internal ranking fields.**

### Cross-Repository Coordination
- Backend (`taskpilot`) and frontend (`taskpilot-frontend`) are separate Git repositories.
- Phase 1 uses separate, coordinated branches and pull requests.
- Phase 1 verification requires reporting the compatible backend and frontend commit hashes together.

### Bounded Self-Correction Rules
- `MAX_FIX_ITERATIONS = 3`.
- If a test or gate fails, at most 3 corrective iterations are permitted.
- Never repeat an identical failed patch.
- **Automatic Rollback is Strictly Forbidden**:
  - The Skill and its automation scripts must NEVER automatically execute destructive Git commands (`git checkout -- .`, `git restore .`, `git reset`, `git clean`, `git stash`).
  - When `MAX_FIX_ITERATIONS` is reached, immediately stop, preserve the working tree, and report `BLOCKED_ITERATION_LIMIT`.

---

## 4. Verification Gates

All changes must pass mandatory gates defined in [`references/verification.md`](references/verification.md):
- **Gate 0: Clean Baseline**: Working tree verified clean on isolated branch.
- **Gate 1: Phase 0 Characterization**: Explicit test weights; no runtime DB dependency; current defects characterized; 3 modes covered. Phase 0 must validate the 1e9 fixed-point scale before any production change. If Phase 0 disproves the scale, execution stops with `BLOCKED_CONTRACT_PENDING`. FIX MODE must not silently choose another scale. Numerical oracles must not be edited during FIX MODE to preserve the scale.
- **Gate 2: Output Boundary**: Any object containing internal ranking state must not be serialized directly into a user-facing payload (H-010). `CandidateScore` currently mixes internal ranking and presentation concerns; Phase 1 establishes an explicit allowlisted presentation boundary. No nested JSON leakage; `confidenceScore` and relative `totalScore` omitted. `scoringModelVersion` is mandatory in serialized recommendation contract and persisted tool logs (optional in prominent PM display).
- **Gate 3: Explanation Safety**: Fit status distinguishes missing data from zero match; Workload marked `UNVERIFIED`; Performance marked `DEFAULT`. Performance DEFAULT is proven from the application source contract. It does not prove that every deployed database row contains 0.5. Non-default deployed values remain runtime-unverified until read-only aggregate inspection is performed. No false 100% claims. `INSUFFICIENT_TO_DIFFERENTIATE` applies only when internal ranking keys, raw Skill Fit, and other applicable business evidence cannot distinguish candidates.
- **Gate 4: Comparator Precision**: Fixed-point key at $10^9$ (`1_000_000_000L`); raw Fit tie-break; `userId` tie-break; input order independence. For candidates with `workload(A) = 10, workload(B) = 50, workload(C) = 90`, verify `score(A) >= score(B) >= score(C)` and `position(A) <= position(B) <= position(C)`.
- **Gate 5: Configuration Safety**: Future invalid configurations rejected at write boundary; invalid existing configurations fail closed; no silent mutations.
- **Gate 6: Cross-Repository Verification**: Backend and frontend tests pass; payload contracts match; approved diff budgets respected.

---

## 5. Anti-Gaming Rules

1. Never delete or weaken assertions.
2. Never expand numerical tolerance without mathematical proof.
3. Never edit numerical oracles during FIX MODE to preserve the scale.
4. Never mock core scoring classes (`ScoreRange`, `HeuristicStrategy`).
5. Never hardcode test candidate IDs or scores in production classes.
6. Never sort using display-rounded `totalScore`.
7. Never lower internal precision ($10^9$) to make tests pass.
8. Never skip tests using `@Disabled` or `assumeTrue`.
9. Never silently rewrite invalid persisted configurations.
10. Never claim success when ranking passed but explanation or UI views failed.

---

## 6. Out-of-Scope Roadmap & Adaptive Blockers

### Explicitly Excluded from Phase 1
- **AI feedback PATCH endpoint ownership validation (HDEF-013)**: Separate security task.
- Structured recommendation snapshots.
- PM accept/override structured decision events.
- Dynamic workload computation and freshness timestamps.
- Performance cycles and member review schemas.
- Task outcome ground truth and `completed_at` timestamps.
- Delivery Reliability modeling.
- Overload warning runtime.
- Shadow comparison execution.
- Adaptive Weight proposals and automated tuning.
- Automatic weight mutation.
- Production database migrations.

### Adaptive Readiness Statement
> **Phase 1 creates an Adaptive-ready scoring and explanation contract.**
> **Phase 1 does NOT make Adaptive Weights operational.**
>
> Adaptive Weights remain strictly blocked by:
> 1. Missing reliable Workload data (static unverified administrative value).
> 2. Missing reliable Performance data (static default `0.50` baseline).
> 3. Missing versioned, structured recommendation snapshots.
> 4. Missing structured PM decision pairs (accept vs override).
> 5. Missing task outcome ground truth.

---

## 7. Document Index

- [Heuristic Decision Register](references/decisions.md)
- [Current Runtime Behavior & Facts](references/current-runtime.md)
- [Known Defects Register](references/known-defects.md)
- [Heuristic Verification Protocol](references/verification.md)
- [Business Contract Invariants](references/business-contract.md)
- [Formula Origin & AHP Derivation](references/formula-origin.md)
- [Architecture & Consumer Map](references/architecture.md)
- [Numerical Oracles](resources/numerical-oracles.md)
- [Normalization Test Specification](resources/normalization-test-spec.md)
- [Expected Completion Output Contract](resources/expected-output.md)
- [Audit Report Template](resources/audit-report-template.md)
