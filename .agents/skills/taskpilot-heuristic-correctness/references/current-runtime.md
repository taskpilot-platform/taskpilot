# Current Runtime Behavior & Facts Reference

> **Notice**: This document captures the verified runtime state of TaskPilot based strictly on repository source, migrations, configuration, and tests.

---

## 1. Verified Runtime Capabilities & Source Truth

### 1.1 Skill Fit ($F$)
- Implemented in `AutoAssignmentService.calculateFitScore` (`AutoAssignmentService.java:256-277`).
- Formula:
  $$\text{matchRatio} = \frac{\text{matched}}{\text{requiredSkills.size()}}$$
  $$\text{avgLevelNormalized} = \frac{\text{totalLevel}}{\text{matched} \times 5.0}$$
  $$\text{Fit} = (\text{matchRatio} \times 0.6) + (\text{avgLevelNormalized} \times 0.4)$$
- Edge Cases & Data Debt:
  - Empty or null required skills returns `1.0` (line 258). Conflates "no requirements" with "perfect match".
  - Missing member skill profile and measured zero-match both return `0.0` (line 272). Conflates insufficient data with measured zero.
  - Null persisted level maps to `0` in `UserAssignmentAdapter.java:45`.
  - Levels $> 5$ cause raw Fit to exceed `1.0` (no clamping).
  - Duplicate required skill names increment `matched` multiple times, distorting match ratio.
  - Duplicate member skills use first-wins resolution (`Collectors.toMap(..., (a, b) -> a)`).

### 1.2 Workload ($L$)
- `users.current_workload` is clamped to $[0, 100]$ and divided by 100 in `AutoAssignmentService.normalizeLoad` (`AutoAssignmentService.java:319-322`).
- Global administrative field without project scope, task mutation update triggers, or freshness timestamp.
- In production data, `current_workload` defaults to `0` for almost all users.
- Provenance is `UNVERIFIED`. A value of zero must not be claimed as proven availability. Approved wording: `"Chưa có dữ liệu workload đáng tin cậy"`.

### 1.3 Performance ($P$)
- `project_members.performance_score` defaults to `0.50` in database migration `V1__init_taskpilot_schema.sql:165`.
- No automatic background writer or performance-cycle evaluation updates `performance_score`.
- Performance DEFAULT is proven from the application source contract.
- It does not prove that every deployed database row contains 0.5.
- Non-default deployed values remain runtime-unverified until read-only aggregate inspection is performed.
- Approved explanation wording: `"Chưa đủ dữ liệu hiệu suất"`.
- `findRecentPerformanceScores` (`ProjectMemberRepository.java:51-56`) queries:
  ```sql
  SELECT pm.performanceScore FROM ProjectMemberEntity pm
  WHERE pm.userId = :userId AND pm.performanceScore IS NOT NULL
  ORDER BY pm.joinedAt DESC
  ```
- The query is not project-scoped (reads across all project memberships of the user) and orders by `pm.joinedAt DESC` (project join timestamp), not evaluation dates or task outcomes.

### 1.4 Confidence Score Semantics
- `resolveConfidence` (`AutoAssignmentService.java:306-313`):
  $$0 \implies 0.0, \quad 1 \implies 0.4, \quad 2 \implies 0.7, \quad \ge 3 \implies 1.0$$
- `evidenceCount` is simply `recentScores.size()` from the cross-project query above.
- Numeric confidence increases solely by adding a user to more project memberships, even if all scores are default `0.50`.
- Has zero mathematical relationship to task completion quality or statistical performance confidence.

### 1.5 Runtime Weight Source (SOURCE_PROVEN)
- **Status**: `SOURCE_PROVEN` (Technical finding from source inspection; not a product decision or approval).
- Effective weights are loaded only from `system_settings["heuristic.weights"]` (`HeuristicConfigProvider.java:76-78`).
- The repository contains:
  - no Java fallback weight mapping;
  - no checked-in SQL seed for `heuristic.weights` (`V1__init_taskpilot_schema.sql:204` is a column comment only);
  - no application-configuration fallback.
- Missing setting or missing mode causes HTTP 500 `BusinessException`.
- Positive supplied weights are normalized so the sum equals 1.0 (`HeuristicConfigProvider.java:204`).
- Effective deployed values remain `RUNTIME_UNVERIFIED` without safe database inspection.
- **Rules for Phase 0**:
  - Tests must specify weights explicitly.
  - Tests must not call documented AHP weights "runtime weights".
  - Characterization tests must be deterministic and database-independent.
  - Environment-specific runtime verification is separate from algorithm tests.
- **Production Fragility**:
  - A fresh migrated database may lack `heuristic.weights` unless an external bootstrap process provides it.
  - This is a separate configuration-foundation concern and must not be silently fixed through undocumented fallback behavior.

### 1.6 Tool-to-UI Rendering & Field Exposure
- `AhpAssignmentAiTools.recommendAndAssignTask` wraps recommendation in `RecommendAndAssignResult preview` and returns `ConfirmationRequiredDto` (`AhpAssignmentAiTools.java:196-208`).
- Emitted via SSE `tool` event (`StreamingToolCoordinator.java:138`).
- Frontend receives event and formats payload via `formatFriendlyToolPayload` (`aiChatHelpers.ts:293-335`).
- `ID_KEY_PATTERN` (`/^id$|id$/i`) is applied only to top-level keys (`aiChatHelpers.ts:329`).
- Nested objects are serialized via `${typeof v === 'object' ? JSON.stringify(v) : v ?? '-'}` (`aiChatHelpers.ts:333`).
- All nested fields inside `preview.recommendation.candidates` (`userId`, `email`, `fitScore`, `loadScore`, `performanceScore`, `confidenceScore`, `totalScore`, `workloadScore`, `currentWorkload`) remain visible in the stringified JSON rendered by `ToolEventCard.tsx:33`.
- No allowlist exists on the current generic tool rendering path.

---

## 2. Process Integrity Note

> **Audit Process Integrity**:
> A previous fact-verification pass read conversation transcript/session files despite an explicit prohibition.
> No repository files were modified by that action.
> Only findings independently supported by repository source `file:line` evidence are accepted into this Skill baseline.
> Future AUDIT, FIX, and VERIFY executions must not use transcripts, session logs, or previous Agent summaries as technical evidence.
> This repository relies strictly on verified source code, tests, migrations, and checked-in configurations.

---

## 3. Foundations Missing for Adaptive Weights

Adaptive Weights and Preference Learning remain strictly out of scope and cannot be operationalized due to the following missing foundations:
1. **Missing Reliable Workload Data**: Workload is a static, manually maintained field without task-completion hooks or freshness timestamps.
2. **Missing Reliable Performance Data**: Performance scores remain static default values (`0.50`) without genuine performance cycles or evaluation records.
3. **Missing Ground Truth on Task Outcomes**: Tasks lack execution duration history, review ratings, or delivery reliability signals.
4. **Missing Structured Recommendation Snapshots**: AI recommendations are not persisted as structured, versioned entities with criterion weights and candidates.
5. **Missing PM Decision Events**: PM accept/override decisions are not tracked as structured training pairs.
