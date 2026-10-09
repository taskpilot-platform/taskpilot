# Business Contract & Invariants Reference

This document defines the non-negotiable business rules, ethical constraints, and mathematical invariants governing TaskPilot's task assignment recommendation system.

---

## 1. Decision Support vs Automation

1. **Role of AI/Heuristics**: The system is exclusively a **decision-support tool** for the Project Manager (PM). It recommends candidate rankings and provides contextual explanations.
2. **Final Authority**: The Project Manager remains the sole decision-maker. The algorithm does not independently dictate project assignments.
3. **Execution Guardrails**: Any automated assignment action (`recommendAndAssignTask`, `assignTaskToMember`) requires explicit human confirmation via `PendingAiActionService`.
4. **No Disciplinary Use**: The scoring system must never be used as an employee disciplinary rating, salary benchmark, or performance penalty mechanism.

---

## 2. Fundamental Monotonicity Invariants

For any two eligible candidates $A$ and $B$ evaluated for task $t$ under identical project context:

### 2.1. Skill Fit Invariant
When workload and performance are equal ($L_A = L_B$ and $P_A = P_B$):
$$\text{Raw Fit}(A) > \text{Raw Fit}(B) \implies \text{Total Score}(A) \ge \text{Total Score}(B) \implies \text{position}(A) \le \text{position}(B)$$
*Rule*: **Higher Skill Fit must never reduce candidate rank.**

### 2.2. Workload Invariant (N-001)
When skill fit and performance are equal ($F_A = F_B$ and $P_A = P_B$):
$$\text{workload}(A) < \text{workload}(B) \implies \text{score}(A) \ge \text{score}(B) \implies \text{position}(A) \le \text{position}(B)$$
*Rule*: **Higher Workload must never improve candidate rank.**

Numerical Contract:
- `workload(A) = 10`
- `workload(B) = 50`
- `workload(C) = 90`
$$\text{score}(A) \ge \text{score}(B) \ge \text{score}(C)$$
$$\text{position}(A) \le \text{position}(B) \le \text{position}(C)$$

Raw workload numerical semantics (H-002, H-011):
- `0`: The stored workload value is zero.
- `100`: The stored workload value is one hundred.
- Workload acts strictly as a **cost / penalty** in the scoring formula.
- **Data Status Contract**: Current workload provenance remains `UNVERIFIED`. The system must NOT interpret zero as proven availability or active idle state. Approved explanation wording: `"Chưa có dữ liệu workload đáng tin cậy"`.

### 2.3. Performance Invariant
When skill fit and workload are equal ($F_A = F_B$ and $L_A = L_B$):
$$\text{Raw Performance}(A) > \text{Raw Performance}(B) \implies \text{score}(A) \ge \text{score}(B) \implies \text{position}(A) \le \text{position}(B)$$
*Rule*: **Higher Performance must never reduce candidate rank.**
*Data Status Contract*: Evaluated on measured synthetic data only. Static default baseline (`0.50`) is not eligible as measured adaptive performance (H-011). Performance DEFAULT is proven from the application source contract. It does not prove that every deployed database row contains 0.5. Non-default deployed values remain runtime-unverified until read-only aggregate inspection is performed. Approved explanation wording: `"Chưa đủ dữ liệu hiệu suất"`.

---

## 3. Fairness & Cold-Start Ethics

1. **Missing History Is Not Poor Performance**:
   - New team members or members without prior cycle data must not be penalized.
   - Default performance is marked `DEFAULT` with approved explanation wording: `"Chưa đủ dữ liệu hiệu suất"`.
2. **Context Integrity**:
   - Workload scores and availability displayed to the user or passed to the LLM must faithfully represent data status.
   - Members with workload = 0 must never be verbalized as "overloaded" or "100% load".
   - Members with unverified workload = 0 must never be claimed as "confirmed idle" or "0 active tasks".
3. **Absence of Negative Bias**:
   - Equal-range handling must not convert an unmeasured team into a fully penalized team.

---

## 4. Human Approval for Weight Adjustments

1. **No Autonomous Weight Drift**:
   - Effective heuristic weights ($w_{fit}, w_{load}, w_{perf}$) must not be automatically mutated by background jobs or unsupervised algorithms (H-005).
2. **Governance Workflow**:
   - Any weight changes require:
     - Explicit business decision approval.
     - Configuration update through authenticated Admin endpoints.
     - Full audit logging and immediate rollback capability.
