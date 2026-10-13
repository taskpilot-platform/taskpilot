# Formula Origin & AHP Derivation Reference

This document records the academic and mathematical origins of the criteria, weights, and scoring formulas used in TaskPilot.

---

## 1. Criteria Definitions

1. **Skill Fit ($F$)**:
   $$\text{match\_ratio} = \frac{\text{matched required skills}}{\text{total required skills}}$$
   $$\text{avg\_level\_normalized} = \frac{\sum \text{level of matched skills}}{\text{matched count} \times 5.0}$$
   $$F_{raw} = 0.6 \times \text{match\_ratio} + 0.4 \times \text{avg\_level\_normalized} \in [0.0, 1.0]$$
2. **Workload Cost ($L$)**:
   $$L_{raw} = \frac{\min(100, \max(0, \text{currentWorkload}))}{100.0} \in [0.0, 1.0]$$
   - *Runtime Finding*: Workload is an unverified global number without task mutation hooks or freshness timestamps (H-011).
3. **Performance Signal ($P$)**:
   $$P_{recent} = 0.5 \cdot S_n + 0.3 \cdot S_{n-1} + 0.2 \cdot S_{n-2}$$
   $$P = \text{confidence} \cdot P_{recent} + (1 - \text{confidence}) \cdot P_{prior} \in [0.0, 1.0]$$
   - *Design Concept*: Confidence based on evaluation cycles ($0 \text{ cycles} \to 0.0$; $1 \text{ cycle} \to 0.4$; $2 \text{ cycles} \to 0.7$; $\ge 3 \text{ cycles} \to 1.0$).
   - *Runtime Source Reality (H-011, H-019)*: Source code reads `project_members.performance_score` across project memberships (ordered by `joinedAt DESC`), NOT genuine review cycles or task outcomes. Confidence reflects membership row count, not statistical confidence.

---

## 2. General Scoring Formula

The documented scoring formula combining the criteria is:
$$\text{Score}(u, t) = w_{fit} \cdot F(u, t) - w_{load} \cdot L(u) + w_{perf} \cdot P(u)$$

Where:
- $F(u, t)$ is normalized Skill Fit.
- $L(u)$ is normalized Workload Cost.
- $P(u)$ is normalized Performance.
- The minus sign before $w_{load} \cdot L$ reflects that workload is a cost metric.

---

## 3. AHP Offline Derivation & Initial Weights

Initial weights were derived using Saaty's Analytic Hierarchy Process (AHP) pairwise-comparison matrices in the system design phase, evaluated under the criteria order $[load, fit, performance]$:

### 3.1. Mode BALANCED
- **Pairwise Matrix**:
  $$\begin{pmatrix} 1 & 3 & 5 \\ 1/3 & 1 & 2 \\ 1/5 & 1/2 & 1 \end{pmatrix}$$
- **Vector (Geometric Mean)**:
  - $w_{load} = 64.8\%$ ($0.648$)
  - $w_{fit} = 23.0\%$ ($0.230$)
  - $w_{perf} = 12.2\%$ ($0.122$)
- **Consistency Ratio**: $CR = 0.4\% < 10\%$ (Consistent).
- **Documented Formula**:
  $$\text{Score}_{BALANCED} = 0.230 F - 0.648 L + 0.122 P$$

### 3.2. Mode URGENT
- **Pairwise Matrix**:
  $$\begin{pmatrix} 1 & 1/9 & 1/9 \\ 9 & 1 & 1 \\ 9 & 1 & 1 \end{pmatrix}$$
- **Vector**:
  - $w_{load} = 5.3\%$ ($0.053$)
  - $w_{fit} = 47.4\%$ ($0.474$)
  - $w_{perf} = 47.4\%$ ($0.474$)
- **Consistency Ratio**: $CR = 0.0\% < 10\%$ (Consistent).
- **Documented Formula**:
  $$\text{Score}_{URGENT} = 0.474 F - 0.053 L + 0.474 P$$

### 3.3. Mode TRAINING
- **Pairwise Matrix**:
  $$\begin{pmatrix} 1 & 5 & 7 \\ 1/5 & 1 & 3 \\ 1/7 & 1/3 & 1 \end{pmatrix}$$
- **Vector**:
  - $w_{load} = 73.1\%$ ($0.731$)
  - $w_{fit} = 18.8\%$ ($0.188$)
  - $w_{perf} = 8.1\%$ ($0.081$)
- **Consistency Ratio**: $CR = 6.8\% < 10\%$ (Consistent).
- **Documented Formula**:
  $$\text{Score}_{TRAINING} = 0.188 F - 0.731 L + 0.081 P$$

---

## 4. Source Locations & Historical Discrepancies

- **Primary Planning Document**: `ke_hoach_ahp_hieu_suat_va_function_calling.md`.
  - *Recorded Discrepancy*: Section 2.1 defines both inverse normalization ($X_{nghich} = \frac{X_{max} - X}{X_{max} - X_{min}}$) and a subtraction sign ($-$) in the total score formula. Applying both simultaneously creates double inversion.
- **Academic Thesis Chapter**: `report/_incoming/CHAPTER_3_12_FINAL.md`.
  - *Recorded Discrepancy*: Example table in 3.12.6 assumes member with workload 0 has $L = 0.00$ and zero penalty. The runtime code implementation of `ScoreRange` returns $1.0$ when all workloads are 0.
- **Technical Architecture Notes**: `report/notes/AI_ARCHITECTURE_NOTES.md`.
  - Reconciles that runtime scoring is heuristic/AHP-inspired, not full pairwise comparison at runtime.
- **Database Schema**: Flyway migration `V2__Add_heuristic_mode_and_performance.sql`.
- **Runtime Configuration (SOURCE_PROVEN)**: Runtime loads weights dynamically from `system_settings["heuristic.weights"]`. No fallback weights exist in Java source and no SQL seed inserts weights (see [Current Runtime Facts](current-runtime.md#15-runtime-weight-source-source_proven)).
