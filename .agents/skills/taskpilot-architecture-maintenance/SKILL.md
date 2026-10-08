---
name: taskpilot-architecture-maintenance
description: Reusable protocol and operational rules for senior software engineers and architecture maintenance agents performing refactoring, forensic reconciliation, boundary enforcement, and failure diagnosis on the TaskPilot backend.
---

# TaskPilot Architecture Maintenance Skill

## 1. Operating Thesis & Core Invariants

TaskPilot is engineered as a **Modular Monolith**. Architecture maintenance must strengthen internal boundaries and clarify module ownership without introducing distributed-system complexity.

### Core Architectural Rules
1. **Modular Monolith Integrity**: Keep the monolithic JVM process. Do NOT introduce microservices, gRPC/HTTP between internal modules, Kafka, RabbitMQ, Redis, or distributed locks unless backed by an approved, explicit business requirement.
2. **Inter-Domain Access via Contracts**: Domain modules (`taskpilot-projects`, `taskpilot-users`) and `taskpilot-ai` interact exclusively through interfaces and DTOs declared in `taskpilot-contracts`. Direct Maven dependencies between domain modules and AI are forbidden. AI module internal persistence (`ai_logs`, `chat_sessions`, `documents`, `document_chunks`) remains directly managed by `taskpilot-ai`.
3. **Resilience & Fault Isolation**: External dependency failure (LLM provider outage, HTTP 404/410/429, API timeout, network partition) must NEVER cause deterministic business or recommendation failures. Always implement bounded timeouts and local fallback mechanisms.
4. **Preserve Specialized Subsystems**: Treat established subsystems (e.g. `com.taskpilot.ai.rag.*`) as bounded capabilities. Do not reorganize, redesign, or touch their ingestion, lease fencing, rate limiting, or vector retrieval unless directly required by verified compiler/test failures.
5. **Tool Execution Locality**: AI tools and system tools execute in-process within the running application JVM.

---

## 2. Repository-First Discovery Protocol

Before touching code or designing changes, inspect the repository as it exists **today**. Never rely solely on historical audits, outdated diagrams, or previous conversation logs.

### Discovery Steps
1. **Module & Directory Layout**: Inspect actual modules (`taskpilot-contracts`, `taskpilot-projects`, `taskpilot-users`, `taskpilot-ai`, `taskpilot-app`, `taskpilot-infrastructure`).
2. **Documentation & ADR Scan**: Check `docs/`, `docs/architecture/`, `docs/implementation/`, `report/`, `ARCHITECTURE_REVIEW.md`, `STATUS.md`.
3. **Build & Dependency Configuration**: Check root and submodule `pom.xml`, dependency versions, compiler preview flags, and active Spring Boot starters.
4. **Active Runtime Configuration**: Check `application.yml`, active profiles, configured beans, and properties.

---

## 3. Source-of-Truth Priority

When documentation, reports, and code conflict, resolve discrepancies using this strict hierarchy:

```text
1. Current source code & build configuration (pom.xml)
2. Current tests and executable behavior
3. Current database migrations (Flyway V* scripts) & schema
4. Current architecture decisions (ADRs)
5. Current implementation plans
6. Older audit / review / recommendation documents
```

*Rule*: If an audit recommendation has already been implemented (e.g. a coordinator class already exists), record the recommendation as **FULFILLED/STALE**. Never re-implement or re-extract already-existing components.

---

## 4. Forensic Reconciliation Loop

For every refactoring or architectural task, execute the reconciliation loop before applying edits:

```text
CURRENT CODE ◄──► CURRENT TESTS ◄──► DOCUMENTATION ◄──► UPCOMING REQUIREMENTS
```

Build a mental or documented reconciliation model:
- **Component**: Class or package under review.
- **Current Responsibility**: What it actually does today.
- **Dependency Direction**: What it imports and what imports it.
- **Existing Boundaries**: Verified interfaces/ports.
- **Observed Problem**: Concrete failure or coupling issue.
- **Historical Recommendation Status**: Fulfilled, Still Valid, or Stale.
- **Minimal Required Change**: The smallest surgical modification that resolves the issue.

---

## 5. Trigger-Based Refactoring

Do **NOT** refactor or decompose code merely because:
- A class has many lines or methods;
- A theoretical diagram looks cleaner;
- A previous audit recommended reorganization without current pain;
- A new package or abstraction pattern looks attractive.

### Legitimate Refactoring Triggers
Refactor only when at least one concrete pressure is proven:
- **Responsibility Collision**: Two unrelated domain features require concurrent edits in the same class.
- **High Change Coupling**: Modifying one component repeatedly breaks unrelated callers.
- **Test Isolation Failure**: A unit test cannot run without booting unnecessary heavyweight dependencies.
- **Security Boundary Violation**: Unsafe capabilities (e.g. arbitrary SQL execution) are exposed to agents or untrusted input.
- **Provider Leakage**: Vendor-specific APIs or retired model IDs leak into core orchestration logic.
- **Runtime Correctness / Fault Vulnerability**: External provider downtime takes down internal business logic.

---

## 6. Hotspot Protection Policy

Identified orchestrators and registries are sensitive hotspots:
- `StreamingChatEngine`
- `SmartRoutingService`
- `ToolCallingRegistryService`

### Operating Rules
- **Bug/Correctness Fixes**: Allowed with minimal surgical diff.
- **Provider Compatibility Changes**: Allowed when retiring dead endpoints or updating routing targets.
- **New Responsibilities**: **Prohibited**. Do not add persistence, heavy caching, new domain logic, or unmanaged tool execution directly into these hotspots.
- **Speculative Decomposition**: Prohibited. Do not split a hotspot simply because of line count unless an explicit refactoring trigger is met.

---

## 7. Abstraction Discipline

Avoid speculative or premature abstractions (e.g. `GenericAgentFramework`, `GenericWorkflowEngine`, `ModelResolverFactory`).
- Implement an abstraction **only** when it isolates concrete variability, enforces a security boundary, or decouples external provider volatility.
- Prefer existing Spring beans, LangChain4j interfaces, and `taskpilot-contracts` ports.
- When adding fallbacks or timeouts, reuse standard Java concurrency primitives (`CompletableFuture`, bounded `future.get(timeout, unit)`) rather than pulling in external reactive or resilience libraries unless already in use.

---

## 8. Autonomous Implementation & Self-Healing Execution Loop

Execute implementation autonomously with self-healing verification:

```text
INSPECT -> MAKE MINIMAL CHANGE -> COMPILE & TEST
  │
  ├──► PASS: Run regression suite -> Verify invariants -> Commit
  │
  └──► FAIL:
        1. Classify failure (compile error, assertion, timeout, environment)
        2. Locate root cause in code or test fixture
        3. Apply minimal repair
        4. Re-run failed check until green
```

### Routine Autonomous Repairs
Fix autonomously without waiting for user intervention:
- Broken imports or renamed package references.
- Missing constructor arguments or bean qualifiers.
- Stale references to deleted deprecated methods.
- Deterministic test fixture updates matching the new contract.

---

## 9. Scope Guard

Autonomous repairs must remain strictly within task scope. Before changing code in an adjacent module or unrelated class, ask:
1. Is this change directly required to satisfy the work order?
2. Is it directly fixing a compiler error or test failure caused by the current task?
3. Does it prevent a security or architectural violation?

If the answer is **NO**, do NOT edit the file. Record the finding under **Unexpected Findings** in the implementation report.

---

## 10. Testing, Regression & Static Verification Protocol

1. **Focused Unit Tests**: Test changed components in isolation with deterministic mocks (zero real network or provider calls in unit tests).
2. **Deterministic Timeout & Fallback Tests**: For resilience logic, test both the success path and the simulated exception/timeout path using fast, deterministic timeouts (e.g., 50–100ms).
3. **Module-Level Regression**: Execute the Maven build for the affected submodule:
   ```bash
   ./mvnw test -pl <submodule-name>
   ```
4. **Static Verification Checklist**:
   - Zero references to removed insecure methods/tools.
   - Zero active references to retired model IDs or dead provider endpoints.
   - Test-only adapters/fixtures relocated to `src/test`.
   - Modularity and contracts boundaries preserved.

---

## 11. Documentation Maintenance Protocol

Architecture maintenance is incomplete until repository documentation matches the new reality:
1. **Reconciliation Report**: Document what was investigated, previous recommendations verified, recommendations marked fulfilled/stale, and changes executed.
2. **Implementation Report**: Record file-by-file changes, rationale, before/after behavior, and test evidence.
3. **Architecture Status**: Update canonical project status with current boundaries, active models, and deferred triggers.
4. **Preserve History**: Mark obsolete sections as `[SUPERSEDED]` or `[IMPLEMENTED]` rather than deleting historical context.

---

## 12. Final Definition of Done Verification

Never declare an architecture task complete until:
- [ ] Repository discovery and forensic reconciliation completed.
- [ ] The minimal justified diff has been applied.
- [ ] Hotspot invariants are preserved (no unauthorized responsibilities added).
- [ ] Focused tests for changed behaviors pass deterministically.
- [ ] Full module regression suite passes cleanly.
- [ ] Static verification checks confirm zero stale or insecure references.
- [ ] Modular monolith boundaries (`taskpilot-contracts` mediation) are verified.
- [ ] Canonical architecture and implementation documents are synchronized with the code.
