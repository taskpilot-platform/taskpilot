# Audit Report Template

Use this template when executing **AUDIT MODE** under `taskpilot-heuristic-correctness`.

---

```markdown
# TASKPILOT HEURISTIC AUDIT REPORT

### 1. Mode & Target Scope
- Operating Mode: AUDIT MODE (Strictly Read-Only)
- Active Repository: [taskpilot / taskpilot-frontend]
- Current Branch: [branch name]
- Inspected Classes: [List of inspected files]

### 2. Repository Integrity
- Working Tree Status: [Clean / Modified]
- HEAD Commit: [commit hash]
- Unauthorized Changes: [None]

### 3. Decisions Reconciled
- Approved Decisions Loaded: [List IDs, e.g. H-001, H-002, H-003, H-004, H-006, H-010..H-019]
- Proposed Decisions Pending: [List IDs, if any]

### 4. Source & Formula Analysis
- Skill Fit Formula: [Observed behavior & bounds]
- Workload Formula: [Observed behavior & bounds]
- Performance Formula: [Observed behavior & bounds]
- Total Score Formula: [Arithmetic sign & mode weights]

### 5. Downstream Consumers
- Tool Handlers: [List of tools consuming scores]
- Frontend Components: [How fields are rendered]
- LLM Explanation Prompt: [Metrics passed to prompt]

### 6. Defect Evidence
- Defect ID: [e.g. HDEF-001 through HDEF-013]
- Code Snippet: [Exact code location]
- Failure Proof: [Mathematical demonstration]

### 7. Numerical Oracle Evaluation
- N-001 (Workload Monotonicity): [PASS / FAIL / RISK]
- N-002 (Inversion Rejection): [PASS / FAIL / RISK]
- N-003 (Equal Zero Workload): [PASS / FAIL / RISK]
- N-004 (Single Candidate): [PASS / FAIL / RISK]
- N-005 (Fit Monotonicity): [PASS / FAIL / RISK]
- N-006 (Performance Monotonicity): [PASS / FAIL / RISK]
- N-007 (No Side Effects): [PASS / FAIL / RISK]
- N-008 through N-016: [Evaluation status]

### 8. Test Coverage Gaps
- Existing Tests: [Summary of current tests]
- Missing Test Scenarios: [Uncovered invariants]

### 9. Proposed Decision Updates
- [Recommendations for Phase 1 / Phase 2]

### 10. Final Status
- Status: [AUDIT_COMPLETE | READY_FOR_DECISION | BLOCKED_CONTRACT_PENDING]
```
