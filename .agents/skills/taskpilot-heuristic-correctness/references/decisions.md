# Heuristic Decision Register

This register governs all technical and business decisions regarding TaskPilot's heuristic candidate scoring.
In FIX MODE, the Agent may only implement changes that strictly conform to approved decisions.

---

## 1. Decision Status Lifecycle Model

Statuses in this register are unambiguous:

| Status | Definition |
| :--- | :--- |
| `PROPOSED` | Technical proposal not yet approved. |
| `PENDING_BUSINESS_CONFIRMATION` | Business-facing behavior awaiting stakeholder confirmation. |
| `APPROVED_FOR_PHASE_1` | Approved only for the current transitional release scope (Phase 1). Not automatically the long-term model. |
| `APPROVED_TECHNICAL_INVARIANT` | Correctness, mathematical, or execution safety invariant that must always hold across all phases. |
| `DEFERRED` | Valid concept explicitly postponed to a future phase. |
| `REJECTED` | The option must not be implemented under the current contract. |
| `IMPLEMENTED` | Production code implements the accepted decision. |
| `VERIFIED` | Implementation passed all defined verification gates. |
| `SUPERSEDED` | A later accepted decision replaces it. |

*Rule*: Do not use `ACCEPTED` without a qualifier when the scope is transitional.
*Rule*: Findings of fact are preserved in `references/current-runtime.md` with source-proven labels; they are not decisions and must not use decision approval statuses.

---

## 2. Decision Catalog (H-001 through H-016, H-018, H-019)

### Decision H-001: Candidate score model
- **Status**: `APPROVED_FOR_PHASE_1`
- **Decision**:
  - For Phase 1, preserve candidate-set relative ranking as a transitional model to minimize behavioral regression during the correctness fix.
  - This decision does not prove that relative Min-Max normalization is the optimal long-term model.
  - Phase 1 must not switch to:
    - absolute bounded scoring;
    - all-benefit scoring;
    - a user-facing Overall Match percentage.
  - Relative normalized values must not be presented as absolute suitability.
  - The long-term score model remains subject to a future shadow comparison after reliable Workload and Performance data exist.
- **Consequences**:
  - Existing relative-ranking architecture remains intact for Phase 1.
  - User-facing explanations must be separated from ranking contributions (see H-010).
  - Future evaluation of absolute and all-benefit models remains required.

---

### Decision H-002: Workload direction invariant
- **Status**: `APPROVED_TECHNICAL_INVARIANT`
- **Decision**:
  - With all other criteria equal, a candidate with lower raw workload must never rank below a candidate with higher raw workload because of the workload criterion.
- **Raw Workload Contract**:
  - `0`: The stored workload value is zero.
  - `100`: The stored workload value is one hundred.
  - Current workload provenance remains unverified; the system must not interpret zero as proven availability.
- **Allowed Scope**: Logic and assertions in `ScoreRange`, `ScoreRanges`, and `HeuristicStrategy` enforcing this monotonicity.

---

### Decision H-003: Unsafe double inversion
- **Status**: `APPROVED_TECHNICAL_INVARIANT`
- **Decision**:
  - The combination of inverted workload normalization (`BENCHMARK_COST`) with subtractive workload scoring is invalid.
  - Invalid future configuration must be rejected at write/validation boundaries.
  - Invalid existing configuration must fail closed rather than silently invert candidate ranking.
  - Persisted settings must not be silently rewritten or overridden.
- **Allowed Scope**: Validation guards in `HeuristicConfigProvider` and `HeuristicStrategy`.

---

### Decision H-004: Equal-range policy
- **Status**: `APPROVED_FOR_PHASE_1`
- **Decision**:
  - When a criterion has no variance across the candidate set ($max \le min$):
    - it must not create artificial ranking differences;
    - its ranking contribution is neutral;
    - its configured weight is not redistributed;
    - its raw business metric remains available for explanation;
    - user-facing output must not substitute the neutral ranking contribution for the raw metric.
  - No automatic effective-weight redistribution is permitted.
- **Precision Note**:
  - Changing an equal-range contribution may alter rounded score boundaries under the current implementation because the current comparator sorts rounded two-decimal scores.
  - Phase 1 must therefore separate internal ranking precision from display rounding (see H-012).

---

### Decision H-005: Automatic weight mutation
- **Status**: `REJECTED`
- **Decision**:
  - TaskPilot must not automatically change effective heuristic weights without:
    - a separately approved design;
    - sufficient trustworthy data;
    - versioned recommendation snapshots;
    - explicit human approval;
    - audit history;
    - bounded change rules;
    - rollback support.

---

### Decision H-006: Scope separation
- **Status**: `APPROVED_TECHNICAL_INVARIANT`
- **Decision**:
  - Phase 1 must exclude:
    - dynamic workload computation;
    - workload freshness timestamps;
    - `STALE` status;
    - `completed_at` timestamps;
    - task outcome calculation;
    - Delivery Reliability;
    - performance cycles;
    - PM member review;
    - self-review;
    - structured recommendation persistence;
    - Adaptive Preference Learning;
    - automatic weight updates;
    - overload warning runtime;
    - sprint heuristic override;
    - dashboard work;
    - employee evaluation policy.

---

### Decision H-007: Pure unit test requirement
- **Status**: `APPROVED_TECHNICAL_INVARIANT`
- **Decision**:
  - Algorithmic correctness tests must be pure unit tests without mocks of the class under test and without external database, Docker, or Spring container dependencies.

---

### Decision H-008: Human approval invariant
- **Status**: `APPROVED_TECHNICAL_INVARIANT`
- **Decision**:
  - Any AI-initiated task assignment write operation (`recommendAndAssignTask`, `assignTaskToMember`) must remain pending in `PendingAiActionService` until explicitly confirmed by the Project Manager.

---

### Decision H-009: Non-overwriting of live heuristic data
- **Status**: `APPROVED_TECHNICAL_INVARIANT`
- **Decision**:
  - Do not create database migrations or synthetic writes that overwrite live member performance or workload data during heuristic scoring fixes.

---

### Decision H-010: Ranking metrics versus explanation metrics
- **Status**: `APPROVED_FOR_PHASE_1`
- **Decision**:
  - Internal ranking state and user-facing recommendation data are separate contracts.
  - Any object containing internal ranking state must not be serialized directly into a user-facing payload.
  - `CandidateScore` currently mixes internal ranking and presentation concerns.
  - Phase 1 must establish a separate allowlisted presentation boundary.
  - **Ranking logic** may use:
    - normalized criteria;
    - ranking contributions;
    - full-precision ranking score;
    - configured weights;
    - normalization details;
    - tie-break inputs.
  - **UI and LLM explanation** may use only an explicit allowlisted view containing approved business-facing fields.
  - User-facing output must not expose by default:
    - full-precision ranking score;
    - relative `totalScore`;
    - ranking contributions;
    - normalization internals;
    - configured weights;
    - `confidenceScore`;
    - internal IDs not required by the action;
    - email unless explicitly required and approved.
- **Evidence Summary**:
  - Current frontend dynamically renders nested tool payload fields using generic JSON formatting (`Object.entries` with `JSON.stringify`).
  - Therefore adding new internal fields or directly serializing internal ranking models exposes them unintentionally unless an explicit allowlist view is used.

---

### Decision H-011: Metric data-status semantics
- **Status**: `APPROVED_FOR_PHASE_1`
- **Approved Statuses**: `MEASURED`, `DEFAULT`, `INSUFFICIENT_DATA`, `UNVERIFIED`.
- **Deferred Status**: `STALE` is `DEFERRED` because no reliable timestamp or workload update source currently exists.
- **Metric Policy**:
  - **Skill Fit**:
    - `MEASURED`: Task contains at least one required skill, member has a non-empty skill profile, and Fit is calculated from available inputs. A measured zero match remains `MEASURED` with value zero.
    - `INSUFFICIENT_DATA`: Task required skills are missing or empty, or the member has no recorded skill profile.
    - Current numeric Fit output may remain backward-compatible for ranking during Phase 1, but explanation status must distinguish these cases.
  - **Workload**:
    - `UNVERIFIED`: The workload field is a global Admin-entered number without reliable provenance, project scope, or freshness timestamp.
    - Zero must not be described as proven availability.
    - A positive value must not be described as fresh or measured merely because it is non-zero.
    - Approved wording: `"Chưa có dữ liệu workload đáng tin cậy"`.
  - **Performance**:
    - `DEFAULT`: Application source contains no confirmed production writer or genuine performance-cycle source.
    - Performance DEFAULT is proven from the application source contract.
    - It does not prove that every deployed database row contains 0.5.
    - Non-default deployed values remain runtime-unverified until read-only aggregate inspection is performed.
    - Approved wording: `"Chưa đủ dữ liệu hiệu suất"`.
  - **Confidence**:
    - `confidenceScore` is not approved for user-facing Phase 1 output.
    - Current confidence is based on the number of non-null project-membership performance rows, not genuine evaluation cycles or task outcomes.
- **Adaptive Eligibility**:
  - `MEASURED`: Potentially eligible in a future accepted Adaptive design.
  - `DEFAULT`: Not eligible as learning evidence.
  - `INSUFFICIENT_DATA`: Not eligible.
  - `UNVERIFIED`: Not eligible.

---

### Decision H-012: Ranking precision and deterministic ordering
- **Status**: `APPROVED_FOR_PHASE_1`
- **Decision**:
  - Display rounding must never affect candidate ranking.
  - The ranking engine must preserve an internal full-precision heuristic score.
  - Candidate ordering must use a deterministic fixed-point ranking key derived from the unrounded heuristic score.
  - The Phase 1 fixed-point scale is: `1_000_000_000` ($10^9$).
  - Equivalent internal quantization:
    $$\text{rankingKey} = \text{Math.round}(\text{fullPrecisionScore} \times 1\,000\,000\,000\text{L})$$
  - Display values may continue to use two-decimal rounding only after the final ordering is established.
- **Fail-Closed Validation & Scale Governance**:
  - Phase 0 must validate the 1e9 fixed-point scale before any production change.
  - If Phase 0 disproves the scale, execution stops with `BLOCKED_CONTRACT_PENDING`.
  - FIX MODE must not silently choose another scale.
  - Numerical oracles must not be edited during FIX MODE to preserve the scale.
- **Comparator Order**:
  1. Fixed-point internal ranking key descending;
  2. Raw Skill Fit descending, using its own deterministic internal comparison;
  3. `userId` ascending.
- **Rules**:
  - Database retrieval order must not determine the final ranking.
  - Display rounding must not determine the final ranking.
  - Relative `totalScore` intended only for presentation must not be the comparator input.
  - Floating-point epsilon comparators are not approved because non-transitive comparison behavior must be avoided.
- **Compatibility Acceptance**:
  - Pairs with different current two-decimal rounded scores must retain their relative order after Phase 1.
  - Pairs currently tied only because of two-decimal rounding may change order according to full-precision ranking and accepted tie-break rules.
  - Full mathematical ties use raw Skill Fit and then `userId`.

---

### Decision H-013: Full-tie recommendation semantics
- **Status**: `APPROVED_FOR_PHASE_1`
- **Decision**:
  - Deterministic ordering is required even when available evidence cannot meaningfully distinguish candidates.
  - `INSUFFICIENT_TO_DIFFERENTIATE` applies only when:
    - internal ranking keys are equal;
    - raw Skill Fit values are equal or unavailable;
    - all other applicable business evidence cannot distinguish candidates;
    - `userId` ordering is the only remaining distinction.
  - A ranking-key tie with different raw Skill Fit is not a full business tie.
  - When all candidates are equal under applicable ranking evidence:
    - an internal deterministic first candidate may still exist;
    - the first candidate must not be described as meaningfully superior;
    - the view or explanation must indicate that available data is insufficient to differentiate candidates;
    - technical tie-breaking must not be presented as business evidence.
- **Approved Conceptual State**: `INSUFFICIENT_TO_DIFFERENTIATE`.
- **Note**: Do not require a database migration. The exact DTO shape may be decided during FIX MODE, but the user-facing semantic invariant is frozen.

---

### Decision H-014: Scoring model versioning
- **Status**: `APPROVED_FOR_PHASE_1`
- **Decision**:
  - Every new recommendation output produced after Phase 1 must identify its scoring contract version.
  - Phase 1 version: `relative-explanation-safe-v2`.
  - Minimum payload field: `scoringModelVersion`.
  - `scoringModelVersion` is mandatory in:
    - serialized recommendation contract;
    - persisted tool-output logs where applicable;
    - future structured snapshots.
  - Prominent PM-facing display is optional. It may appear only in expandable technical details unless business requirements approve primary display.
  - Existing historical records without a version remain `legacy-unversioned`. Do not backfill old logs with an assumed version.
  - In the future, structured snapshots must also include heuristic mode, effective configured weights, normalization contract, and `createdAt`.
  - No migration is required in Phase 1 solely for model versioning.

---

### Decision H-015: User-facing candidate presentation
- **Status**: `APPROVED_FOR_PHASE_1`
- **Decision**:
  - Phase 1 user-facing candidate output displays:
    - deterministic rank;
    - approved identity/display fields;
    - raw Skill Fit when meaningful;
    - Workload value with `UNVERIFIED` status;
    - Performance with `DEFAULT` status;
    - concise explanation;
    - heuristic mode only if useful;
    - `scoringModelVersion` for technical traceability (prominent PM-facing display is optional; it may appear only in expandable technical details unless business requirements approve primary display).
  - Phase 1 must not present:
    - relative `totalScore` as Overall Match;
    - relative `totalScore` as suitability percentage;
    - normalized Fit as absolute Skill Fit;
    - normalized Load as actual workload percentage;
    - normalized Performance as measured historical performance;
    - `confidenceScore` as statistical confidence;
    - technical tie-break as evidence of superiority.
- **Required Wording Corrections**:
  - Do not claim `"historical performance"` when the source is only the default baseline.
  - Do not claim `"currently idle"` or `"no tasks"` from `UNVERIFIED` workload.
  - Do not claim `"best candidate"` when recommendation status is `INSUFFICIENT_TO_DIFFERENTIATE`.

---

### Decision H-016: User-facing payload allowlist
- **Status**: `APPROVED_TECHNICAL_INVARIANT`
- **Decision**:
  - User-facing AI tool payloads must use an explicit allowlist.
  - A top-level ID denylist is insufficient because nested objects may be JSON-stringified with all internal fields intact.
  - Objects containing internal ranking state must not be emitted directly to the generic frontend renderer.
  - Phase 1 must prevent accidental display of:
    - nested `userId`;
    - `projectId`;
    - `taskId` unless required for the confirmed action;
    - email unless explicitly approved;
    - full-precision score;
    - ranking contributions;
    - normalized internal values;
    - `confidenceScore`;
    - configured weights;
    - normalization details.
  - Do not rely on `ID_KEY_PATTERN` as the primary security or presentation boundary.

---

### Decision H-018: Skill Fit data-quality boundaries
- **Status**: `DEFERRED`
- **Findings**:
  - Missing member skill profile and measured zero match currently both produce numeric Fit `0.0`.
  - Missing task-required skills currently produces numeric Fit `1.0`.
  - Null persisted skill level is mapped to `0`.
  - Levels outside 1..5 may produce raw Fit outside $[0, 1]$.
  - Duplicate required skill names may distort scoring.
  - Duplicate member skill names use first-wins behavior.
- **Phase 1 Requirement**:
  - Explanation status must distinguish insufficient data from measured outcomes (see H-011).
- **Deferred Work**:
  - Schema/validation hardening, deduplication policy, legacy data cleanup, level-range enforcement.
  - Do not expand Phase 1 into a full skill-data migration.

---

### Decision H-019: Confidence score semantics
- **Status**: `APPROVED_FOR_PHASE_1`
- **Decision**:
  - Current `confidenceScore` is not an approved user-facing confidence metric.
  - It is derived from the count of non-null project-membership performance rows, not genuine performance cycles, task outcomes, or statistical evidence.
  - Default performance rows may increase the current confidence value.
  - Therefore Phase 1 user-facing output must omit `confidenceScore`.
  - The internal field may remain temporarily if required for backward compatibility, but it must not appear through the user-facing allowlisted view.
  - A genuine confidence model is deferred until trustworthy performance-cycle or task-outcome evidence exists.
