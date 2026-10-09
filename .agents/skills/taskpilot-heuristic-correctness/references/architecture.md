# Architecture & Pipeline Mapping Reference

This document maps the end-to-end execution flow of task assignment recommendation in TaskPilot.

---

## 1. End-to-End Pipeline Map

```text
1. User Request (Web Chat / API)
   │
   ▼
2. SmartRoutingService.route()
   ├── Evaluates Gatekeeper LLM / keyword intent (requiresAHP)
   └── Selects Reasoning Model (e.g., gpt-oss-120b, DeepSeek-R1)
   │
   ▼
3. AhpAssignmentAiTools
   ├── @Tool recommendAssignmentCandidates (Read-only ranking)
   ├── @Tool recommendTaskAssignmentCandidates (Task-specific ranking)
   └── @Tool recommendAndAssignTask (Write operation with confirmation)
   │
   ▼
4. AutoAssignmentService.recommendCandidates()
   ├── ProjectPort.findById(projectId) -> reads projects.heuristic_mode
   ├── ProjectMemberPort.findProjectMembers(projectId) -> fetches project team
   ├── Filters out DEACTIVATED and OOO members
   ├── For each candidate:
   │     ├── UserSkillPort -> calculateFitScore() -> F_raw in [0, 1] (data status: MEASURED / INSUFFICIENT_DATA)
   │     ├── UserPort.currentWorkload -> normalizeLoad() -> L_raw in [0, 1] (data status: UNVERIFIED)
   │     └── ProjectMemberPort.findRecentPerformanceScores -> calculateTimeDecayPerformanceScore() -> P_raw (status: DEFAULT)
   │
   ▼
5. ScoreRanges.from(rawScores)
   ├── Calculates min and max for fit, load, performance across candidate set
   │
   ▼
6. HeuristicStrategyFactory.resolve(mode)
   ├── Fetches HeuristicConfig from HeuristicConfigProvider (cached from system_settings)
   └── Instantiates Strategy (BalancedHeuristicStrategy / Urgent / Training)
   │
   ▼
7. Strategy Execution
   ├── strategy.normalize(rawScores, ranges) -> NormalizedScores
   └── strategy.score(normalized) -> fullPrecisionScore = (w_fit * F) - (w_load * L) + (w_perf * P)
   │
   ▼
8. Candidate Ranking & Contract Partitioning (H-010, H-012)
   ├── Derives fixed-point rankingKey = Math.round(fullPrecisionScore * 1_000_000_000L) (1e9 scale validated in Phase 0)
   ├── Sorts by: (1) rankingKey descending, (2) raw Fit descending, (3) userId ascending
   ├── When ranking keys, raw Skill Fit, and other evidence cannot distinguish candidates, assign INSUFFICIENT_TO_DIFFERENTIATE status (H-013)
   ├── Tags scoringModelVersion = "relative-explanation-safe-v2" (mandatory in serialized contract/logs; optional in prominent PM display) (H-014, H-015)
   └── Assembles allowlisted user-facing view (omits internal ranking state, totalScore, confidence, email, internal IDs) (H-016)
   │
   ▼
9. LLM Explanation (H-010, H-015)
   ├── AutoAssignmentService.generateExplanation(top3)
   └── Injects raw metrics with status labeling (unverified workload, default performance)
   │
   ▼
10. Action Packaging & Delivery
    ├── Read-only: Returned to chat / API with allowlisted candidate summary
    └── Write: Encapsulated in PendingAiActionService with actionId for PM approval
```

---

## 2. Authoritative Source Files

Before executing any fix, the Agent must read and revalidate these active production files:

### AI & Scoring Core (`taskpilot-ai`)
- `AutoAssignmentService.java`: Candidate aggregation, filtering, formula orchestrator, explanation generator.
- `AhpAssignmentAiTools.java`: LangChain4j AI tool definitions and parameter binders.
- `HeuristicStrategy.java`: Strategy interface defining `normalize()` and `score()`.
- `BalancedHeuristicStrategy.java`: Strategy implementation for `BALANCED`.
- `UrgentHeuristicStrategy.java`: Strategy implementation for `URGENT`.
- `TrainingHeuristicStrategy.java`: Strategy implementation for `TRAINING`.
- `HeuristicStrategyFactory.java`: Factory resolving strategy from mode string.
- `HeuristicConfigProvider.java`: Dynamic parser and in-memory cache for `system_settings`.
- `HeuristicWeights.java`: Record holding `(fitWeight, loadWeight, performanceWeight)`.
- `HeuristicNormalization.java`: Enum defining `BENCHMARK_BENEFIT` and `BENCHMARK_COST`.
- `ScoreRange.java`: Normalization range logic for individual criteria.
- `ScoreRanges.java`: Container calculating ranges across candidate collection.
- `CandidateScore.java`: Current DTO mixing internal ranking and presentation concerns; any object containing internal ranking state must not be serialized directly into user-facing output; Phase 1 establishes an allowlist view (H-010).
- `PendingAiActionService.java`: In-memory human-in-the-loop confirmation registry.

### Contracts & Ports (`taskpilot-contracts`)
- `ProjectMemberPort.java`: Member listing and recent performance scores.
- `ProjectPort.java`: Project heuristic mode resolution.
- `UserPort.java`: User profile and workload data.
- `UserSkillPort.java`: User skill proficiencies.

### Frontend Consumers (`taskpilot-frontend`)
- `src/components/ai/ToolEventCard.tsx`: Terminal-style markdown card rendering candidate scores.
- `src/components/ai/aiChatHelpers.ts`: JSON parsing and payload formatting (`formatFriendlyToolPayload`).
- `src/components/ai/CombinedConfirmAndPlanCard.tsx`: PM confirmation UI.

*Invariant*: Never rely solely on this static architecture file. Always inspect the current repository source state before preparing a patch.
