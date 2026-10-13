# Numerical Oracles Reference

> **Freeze Rule**: The numerical expectations in this document are frozen and immutable during FIX MODE.
> The Agent must never adjust oracle values to match erroneous production output.

---

## N-001: Workload Monotonicity
- **Inputs**: Candidates have equal Fit and Performance:
  - `workload(A) = 10`
  - `workload(B) = 50`
  - `workload(C) = 90`
- **Expected Invariant**:
  $$\text{score}(A) \ge \text{score}(B) \ge \text{score}(C)$$
  $$\text{position}(A) \le \text{position}(B) \le \text{position}(C)$$
- **Governing Decision**: H-002 (`APPROVED_TECHNICAL_INVARIANT`).

---

## N-002: Unsafe Inversion Rejection
- **Input**: An invalid workload direction combination (e.g. configuring `load = BENCHMARK_COST` while scoring formula subtracts workload).
- **Expected Invariant**:
  - Must be rejected at validation boundaries, OR
  - Must fail closed without inverting ranking.
- **Strict Prohibitions**:
  - Must NOT invert ranking (overloaded candidate must not rank above available candidate).
  - Must NOT silently rewrite persisted configuration.
- **Governing Decision**: H-003 (`APPROVED_TECHNICAL_INVARIANT`).

---

## N-003: Equal Zero Workload
- **Inputs**: All candidates have equal `workload = 0`.
- **Expected Contract**:
  - Workload must NOT create artificial ranking differences.
  - Workload ranking contribution is neutral.
  - Configured weights are NOT redistributed.
  - Raw workload remains `UNVERIFIED`.
  - UI and LLM must NOT show measured `Load: 100%`.
  - No `NaN` or `Infinity`.
- **Governing Decision**: H-004 (`APPROVED_FOR_PHASE_1`).

---

## N-004: Single Candidate Stability
- **Input**: Exactly one candidate: `fit = 0.8, workload = 0, performance = 0.5`.
- **Expected Contract**:
  - Recommendation remains structurally valid.
  - No arithmetic exceptions, `NaN`, or `Infinity`.
  - Raw Skill Fit is preserved separately from ranking contribution.
  - Equal-range normalization must NOT make all user-facing metrics 100%.
  - Performance is shown as default/insufficient data (`DEFAULT`).
  - Workload is shown as unverified (`UNVERIFIED`).
  - Relative `totalScore` is NOT user-facing.
- **Governing Decision**: H-001, H-004, H-010 (`APPROVED_FOR_PHASE_1`).

---

## N-005: Fit Monotonicity
- **Inputs**: With Workload and Performance equal:
  - Candidate A: `fit = 0.90`
  - Candidate B: `fit = 0.85`
  - Candidate C: `fit = 0.80`
- **Expected Invariant**:
  $$\text{score}(A) \ge \text{score}(B) \ge \text{score}(C)$$
  $$\text{position}(A) \le \text{position}(B) \le \text{position}(C)$$
- **Data Status Contract**:
  - Measured zero match and missing skill profile must remain distinguishable through data status (`MEASURED` vs `INSUFFICIENT_DATA`).
- **Governing Decision**: H-011 (`APPROVED_FOR_PHASE_1`).

---

## N-006: Performance Monotonicity (Synthetic Measured Only)
- **Inputs**: With Fit and Workload equal across explicitly measured synthetic data:
  - Candidate A: `performance = 0.90`
  - Candidate B: `performance = 0.50`
  - Candidate C: `performance = 0.20`
- **Expected Invariant**:
  $$\text{position}(A) \le \text{position}(B) \le \text{position}(C)$$
- **Constraint**:
  - Default baseline performance values are NOT eligible as measured adaptive evidence.
  - Performance DEFAULT is proven from the application source contract; does not prove every deployed DB row is 0.5.
- **Governing Decision**: H-011 (`APPROVED_FOR_PHASE_1`).

---

## N-007: No Side Effects
- **Constraints**:
  - Verification must NOT confirm a pending action.
  - Verification must NOT modify task assignees or task status.
  - Verification must NOT modify `system_settings` or heuristic weights.
  - Verification must NOT modify member performance scores.
  - Verification must NOT write to a shared database.
- **Governing Decision**: H-008, H-009 (`APPROVED_TECHNICAL_INVARIANT`).

---

## N-008: Ranking Independent of Display Rounding
- **Expected Contract**:
  - Candidate ranking must use the Phase 1 fixed-point internal ranking key derived from full precision ($10^9$ scale).
  - Two-decimal display rounding must NEVER influence candidate ordering.
- **Fail-Closed Validation**:
  - Phase 0 must validate the 1e9 fixed-point scale before any production change.
  - If Phase 0 disproves the scale, execution stops with `BLOCKED_CONTRACT_PENDING`.
  - FIX MODE must not silently choose another scale.
  - Numerical oracles must not be edited during FIX MODE to preserve the scale.
- **Governing Decision**: H-012 (`APPROVED_FOR_PHASE_1`).

---

## N-009: Current Rounded-Order Compatibility
- **Expected Contract**:
  - Any candidate pair with different current two-decimal rounded ranking scores must preserve its relative order after Phase 1.
  - Pairs previously tied only due to two-decimal rounding may be resolved by the Phase 1 comparator according to full-precision scoring and tie-break rules.
- **Governing Decision**: H-012 (`APPROVED_FOR_PHASE_1`).

---

## N-010: Deterministic Ordering
- **Expected Contract**:
  - Reversing input candidate list must produce identical final ordering.
  - Comparator order:
    1. Fixed-point ranking key descending ($10^9$ scale);
    2. Raw Skill Fit descending;
    3. `userId` ascending.
- **Governing Decision**: H-012 (`APPROVED_FOR_PHASE_1`).

---

## N-011: Full Tie Semantics
- **Expected Contract**:
  - `INSUFFICIENT_TO_DIFFERENTIATE` applies only when:
    - internal ranking keys are equal;
    - raw Skill Fit values are equal or unavailable;
    - all other applicable business evidence cannot distinguish candidates;
    - `userId` ordering is the only remaining distinction.
  - A ranking-key tie with different raw Skill Fit is NOT a full business tie.
  - When all candidates are equal under applicable ranking evidence:
    - Internal ordering remains deterministic;
    - User-facing result is marked `INSUFFICIENT_TO_DIFFERENTIATE`;
    - The first candidate is NOT described as meaningfully superior.
- **Governing Decision**: H-013 (`APPROVED_FOR_PHASE_1`).

---

## N-012: Performance Equal-Range Explanation
- **Expected Contract**:
  - Equal default performance values must NOT be displayed as `Performance: 100%`.
  - Performance DEFAULT is proven from the application source contract. It does not prove that every deployed database row contains 0.5. Non-default deployed values remain runtime-unverified until read-only aggregate inspection is performed.
  - Approved wording: `"Chưa đủ dữ liệu hiệu suất"`.
- **Governing Decision**: H-011, H-015 (`APPROVED_FOR_PHASE_1`).

---

## N-013: Workload Explanation
- **Expected Contract**:
  - `UNVERIFIED` workload must NOT be described as measured availability, active-task count, or confirmed idle state.
  - Approved wording: `"Chưa có dữ liệu workload đáng tin cậy"`.
- **Governing Decision**: H-011, H-015 (`APPROVED_FOR_PHASE_1`).

---

## N-014: Payload Allowlist
- **Expected Contract**:
  - Any object containing internal ranking state must not be serialized directly into a user-facing payload.
  - User-facing recommendation payload must NOT expose:
    - `email`;
    - nested `userId` unless explicitly required;
    - internal `totalScore`;
    - full-precision score;
    - ranking contributions;
    - `confidenceScore`;
    - configured weights;
    - normalization internals.
- **Governing Decision**: H-010, H-016 (`APPROVED_TECHNICAL_INVARIANT`).

---

## N-015: Presentation Contract and Scoring Model Version
- **Expected Contract**:
  - New Phase 1 B1 recommendation outputs include:
    $$\text{presentationContractVersion} = \text{"allowlisted-view-v1"}$$
    $$\text{scoringModelVersion} = \text{"relative-rounded-v1"}$$
  - `presentationContractVersion` and `scoringModelVersion` are mandatory in serialized recommendation contract, persisted tool-output logs where applicable, and future structured snapshots.
  - Prominent PM-facing display is optional (it may appear only in expandable technical details unless business requirements approve primary display).
- **Governing Decision**: H-014, H-015 (`APPROVED_FOR_PHASE_1`).

---

## N-016: Runtime-Independent Tests
- **Expected Contract**:
  - Phase 0 tests must use explicit test weights and do not depend on `system_settings` or a live database.
- **Governing Finding**: Runtime weight source is `SOURCE_PROVEN` (`references/current-runtime.md#15-runtime-weight-source-source_proven`).
