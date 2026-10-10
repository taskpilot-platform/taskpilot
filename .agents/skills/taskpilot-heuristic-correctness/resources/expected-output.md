# Expected Completion Output Contract

Every execution of the `taskpilot-heuristic-correctness` Skill must conclude with a standardized, verifiable summary matching this schema.

---

## 1. Terminal Status Vocabulary

The final execution status must be exactly one of:

| Status | Description |
| :--- | :--- |
| `PHASE_0_READY` | Phase 0 characterization tests defined and ready for execution. |
| `PHASE_0_VERIFIED` | Phase 0 characterization tests pass cleanly; baseline behaviors frozen. |
| `PHASE_1_READY` | Prerequisites satisfied; target Phase 1 decisions ready for implementation. |
| `PHASE_1_VERIFIED` | Phase 1 scoring correction and explanation-safe contract fully verified across backend and frontend. |
| `BLOCKED_CONTRACT_PENDING` | Required decision is still `PROPOSED` or unconfirmed. |
| `BLOCKED_SCOPE_EXPANSION` | Fix attempted changes outside the approved file budget. |
| `BLOCKED_TEST_FAILURE` | Characterization or regression test failed unexpectedly. |
| `BLOCKED_REGRESSION` | Unrelated module tests failed during regression execution. |
| `BLOCKED_ITERATION_LIMIT` | Reached `MAX_FIX_ITERATIONS = 3` without passing all verification gates. |
| `BLOCKED_CROSS_REPO_CONTRACT` | Backend and frontend payload contracts do not match or are misaligned. |
| `BLOCKED_PAYLOAD_EXPOSURE` | Output allowlist failed; internal ranking or candidate fields leaked. |
| `BLOCKED_CONFIG_SAFETY` | Configuration validation failed or allowed unsafe double inversion. |

---

## 2. Standard Execution Report Schema

```markdown
# TASKPILOT HEURISTIC CORRECTNESS EXECUTION REPORT

## 1. Execution Overview
- Phase: [Phase 0 / Phase 1]
- Operating Mode: [AUDIT / FIX]
- Decision IDs: [e.g. H-001, H-004, H-010, H-012]
- Presentation Contract Version: [e.g. allowlisted-view-v1]
- Scoring Model Version: [e.g. relative-rounded-v1]
- Backend Source Commit: [git rev-parse HEAD]
- Frontend Source Commit: [git rev-parse HEAD]
- Final Status: [Terminal Status Enum]

## 2. Configuration & Precision Metrics
- Explicit Test Weights: [fit, load, perf values / N/A]
- Internal Score Precision: [1e9 fixed-point / full double]
- Display Precision: [2 decimal places]
- Iteration Count: [1 / 2 / 3 / N/A]

## 3. Test & Verification Matrix
- Focused Unit Tests: [PASS / FAIL]
- Module Regression Tests: [PASS / FAIL]
- Frontend Rendering Tests: [PASS / FAIL]
- Numerical Oracles Result: [PASS / FAIL (N-001 through N-016)]
- Payload Contract Result: [PASS / FAIL (H-010, H-016)]
- Configuration Safety Result: [PASS / FAIL (H-003)]

## 4. Scope & File Audit
- Changed Backend Files: [List of files within budget]
- Changed Frontend Files: [List of files within budget]
- Security Exclusions: [Confirmed no secrets, no auth bypass, HDEF-013 excluded]
- Unresolved Risks: [List of deferred debt or runtime uncertainties]

## 5. Next Recommended Step
[Clear actionable instruction for developer or human reviewer]
```
