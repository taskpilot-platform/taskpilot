# Heuristic Verification Protocol Reference

This document defines the mandatory verification gates and rules required to validate Phase 0 characterization tests and Phase 1 scoring fixes.

---

## 1. Process Integrity Invariant

> **Notice**: Technical verification relies strictly on repository source code, migrations, configurations, and unit tests.
> No conversation transcripts, session logs, or past agent summaries may be used as technical evidence.
> Verification must be reproducible solely from repository assets.

---

## 2. Mandatory Verification Gates

### Gate 0: Clean Baseline
- **Check**: Working tree clean on isolated branch created from latest `origin/main`.
- **Command**:
  ```powershell
  git status --short
  git diff --name-only
  git ls-files --others --exclude-standard
  ```
- **Exit Condition**: If any unauthorized untracked or modified files exist before starting, execution must halt.

---

### Gate 1: Phase 0 Characterization
- **Requirements**:
  - Tests use **explicit test weights** ($w_{fit}, w_{load}, w_{perf}$) and do not depend on `system_settings` or a live database.
  - Current rounded-comparator defect (HDEF-006) is reproduced in test code.
  - Full-precision score preservation is verified.
  - Fixed-point comparator at $10^9$ (`1_000_000_000L`) scale is verified.
  - **Fail-Closed Validation**:
    - Phase 0 must validate the 1e9 fixed-point scale before any production change.
    - If Phase 0 disproves the scale, execution stops with `BLOCKED_CONTRACT_PENDING`.
    - FIX MODE must not silently choose another scale.
    - Numerical oracles must not be edited during FIX MODE to preserve the scale.
  - Negative score handling (from subtractive workload) is verified.
  - All three strategic modes (`BALANCED`, `URGENT`, `TRAINING`) are covered.
  - Tests execute purely in-memory with zero Docker, container, or network dependencies.

---

### Gate 2: Output Boundary
- **Requirements**:
  - Any object containing internal ranking state must not be serialized directly into a user-facing payload (H-010).
  - `CandidateScore` currently mixes internal ranking and presentation concerns; Phase 1 establishes an explicit allowlisted presentation boundary.
  - Nested generic JSON rendering no longer exposes internal fields (`userId`, internal scores, weights).
  - `confidenceScore` is completely absent from user-facing views (H-019).
  - Relative `totalScore` is completely absent from user-facing views.
  - User `email` is absent unless explicitly approved and required.
  - `scoringModelVersion` is mandatory in serialized recommendation contract, persisted tool-output logs, and future structured snapshots; prominent PM-facing display is optional (H-014, H-015).

---

### Gate 3: Explanation Safety
- **Requirements**:
  - Skill Fit explanation status distinguishes missing data (`INSUFFICIENT_DATA`) from measured zero match (`MEASURED`).
  - Workload is explicitly marked `UNVERIFIED` (approved wording: `"Chưa có dữ liệu workload đáng tin cậy"`).
  - Performance is explicitly marked `DEFAULT` (approved wording: `"Chưa đủ dữ liệu hiệu suất"`).
  - Performance DEFAULT is proven from the application source contract. It does not prove that every deployed database row contains 0.5. Non-default deployed values remain runtime-unverified until read-only aggregate inspection is performed.
  - No claims of `"historical performance"` when the underlying source is the default `0.50` baseline.
  - No false `Load: 100%` on equal zero workload.
  - No false `Performance: 100%` on equal default performance.
  - Single candidate does not show artificial 100% scores across criteria.
  - `INSUFFICIENT_TO_DIFFERENTIATE` applies only when internal ranking keys are equal, raw Skill Fit values are equal or unavailable, and all other applicable business evidence cannot distinguish candidates (`userId` ordering is the only remaining distinction). A ranking-key tie with different raw Skill Fit is not a full business tie. Full ties must never be presented as candidate superiority (H-013).

---

### Gate 4: Comparator Precision & Determinism
- **Requirements**:
  - Display rounding (2 decimal places) does NOT influence candidate ranking.
  - Ranking key uses fixed-point $10^9$ quantization:
    $$\text{rankingKey} = \text{Math.round}(\text{fullPrecisionScore} \times 1\,000\,000\,000\text{L})$$
  - Tie-break order is strictly:
    1. Fixed-point ranking key descending;
    2. Raw Skill Fit descending;
    3. `userId` ascending.
  - Workload monotonicity invariant: for candidates with `workload(A) = 10, workload(B) = 50, workload(C) = 90`, verify `score(A) >= score(B) >= score(C)` and `position(A) <= position(B) <= position(C)`.
  - Reversing input candidate list produces identical final ranking (order independence).
  - Zero dependence on database retrieval order.

---

### Gate 5: Configuration Safety
- **Requirements**:
  - Future invalid configurations (e.g. `BENCHMARK_COST` with subtractive scoring) are rejected at the write boundary.
  - Existing invalid configurations fail closed at the read boundary rather than inverting ranking.
  - Zero silent mutation or rewriting of persisted settings.
  - Zero database migrations required.

---

### Gate 6: Cross-Repository Verification
- **Requirements**:
  - Backend tests pass cleanly via `.\mvnw.cmd test -pl taskpilot-ai -o`.
  - Frontend rendering tests pass cleanly.
  - Backend output DTO contract matches frontend view expectations.
  - Both repository diffs stay within their approved file budgets.
  - **Hard Rule**: No deployment of scoring corrections without matching view-contract changes.

---

## 3. Strict Non-Destructive Stop Protocol

- **Automatic Rollback is Strictly Forbidden**: The Skill and its automation scripts must NEVER automatically execute destructive Git commands:
  - `git checkout -- .`
  - `git restore .`
  - `git reset` / `git reset --hard`
  - `git clean`
  - `git stash`
- When `MAX_FIX_ITERATIONS = 3` is reached without passing all gates:
  1. Stop modifying files immediately.
  2. Preserve the working tree exactly as it is for inspection.
  3. Report status as `BLOCKED_ITERATION_LIMIT`.
  4. Provide full diff and failed assertion details for human review.
