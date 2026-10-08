# TaskPilot AI — Architecture Reconciliation Report

**Date:** September 2026  
**Auditor / Agent:** Senior Software Architect / Architecture Maintenance Agent  
**Branch:** `refactor` (Derived from working tree, `main` completely untouched)  
**Status:** Canonical Reconciliation

---

## 1. Scope & Objective

This report reconciles previous architectural audits (`AI_MODEL_VENDOR_AUDIT_2026-09.md`, `AI_CHAT_FRONTEND_AUDIT_2026-09.md`, and historical audit recommendations) against the current source code, build definitions, and test suites in the TaskPilot repository as of September 2026.

Its goal is to prevent speculative re-implementation, identify stale recommendations that have already been addressed in the codebase, document verified P0 architectural correctness issues, and record non-negotiable boundaries.

---

## 2. Repository State Inspected

The following modules, descriptors, and configurations were inspected:

| Path | Responsibility | Observed Boundary / State |
| :--- | :--- | :--- |
| `taskpilot-contracts` | Cross-module DTOs & Ports | `ProjectMemberPort`, `TaskCommandPort`, `ProjectInsightsPort`, `MemberAnalyticsPort`. In-process contract layer. |
| `taskpilot-ai` | AI Agents, Tool Calling, Heuristics, RAG | In-process JVM module. Depends only on `taskpilot-contracts` for domain access. Direct DB access limited to AI-owned schemas (`ai_logs`, `chat_sessions`, `documents`, `document_chunks`). |
| `taskpilot-projects` | Core Project, Task & Sprint domain | Accessible to AI strictly via `taskpilot-contracts`. |
| `taskpilot-users` | Authentication & User Management | Accessible to AI strictly via `taskpilot-contracts`. |
| `taskpilot-infrastructure` | Cross-cutting tech utilities | Shared infrastructure. |
| `taskpilot-app` | Monolith bootstrap & config | Hosts `application.yml` and Spring Boot application main. |

---

## 3. Current Architecture Snapshot

### 3.1 Architecture Model
- **Pattern:** Modular Monolith within a single JVM process.
- **Inter-Module Communication:** Synchronous in-memory method invocation via Spring dependency injection and port interfaces (`taskpilot-contracts`).
- **Distributed Tech Policy:** Zero Kafka, Zero RabbitMQ, Zero Redis, Zero microservice RPC (ADR-001 preserved).
- **RAG Subsystem:** Completely bounded under `com.taskpilot.ai.rag.*`. Utilizes PostgreSQL pgvector, Apache Tika, LangChain4j, sliding-window rate limiting (`EmbeddingGateway`), and optimistic lease fencing (`processing_version`). Fully operational and preserved.

---

## 4. Previous Recommendations Verified

We audited historical recommendations from architectural documents and classified their current state:

| Historical Recommendation | Current Codebase State | Classification | Action Taken |
| :--- | :--- | :--- | :--- |
| **Extract `StreamingToolCoordinator`** | Class exists at `com.taskpilot.ai.streaming.coordinator.StreamingToolCoordinator` | **ALREADY IMPLEMENTED / FULFILLED** | Do NOT re-extract or modify. |
| **Extract `TimeoutFallbackHandler`** | Class exists at `com.taskpilot.ai.streaming.engine.TimeoutFallbackHandler` | **ALREADY IMPLEMENTED / FULFILLED** | Do NOT re-extract or modify. |
| **Extract `PendingAiActionService`** | Class exists at `com.taskpilot.ai.service.PendingAiActionService` | **ALREADY IMPLEMENTED / FULFILLED** | Human-in-the-loop actions already decoupled. |
| **Eliminate Raw SQL Backdoor** | `executeQuerySql` in `SystemAiTools` with direct `JdbcTemplate` execution | **STILL VALID (P0 Security Flaw)** | **Executed**: Completely removed from `SystemAiTools`, `TaskPilotAiTools`, and `ToolCallingRegistryService`. |
| **Decouple AHP Heuristic from LLM** | `AutoAssignmentService` coupled to `deepSeekReasoningModel` without timeout | **STILL VALID (P0 Runtime Flaw)** | **Executed**: Bound to `geminiFlashModel` with bounded 5000ms timeout and local deterministic fallback. |
| **Clean Up Dead GitHub Models** | `AiModelConfig` had `@Bean` definitions pointing to dead `models.inference.ai.azure.com` | **STILL VALID (P0 Crash Flaw)** | **Executed**: Removed GitHub beans, properties, and enum branches. |
| **Eliminate Retired Groq Model ID** | Hardcoded `"llama-3.3-70b-versatile"` in `StreamingChatEngine` & `IntermediateResponseStreamer` | **STILL VALID (P0 Crash Flaw)** | **Executed**: Replaced hardcoded string with dynamic resolution via `routingService.getReasoningTextModel()`. |
| **Relocate Fake Adapters to Test Scope** | `com.taskpilot.ai.adapter.fake.*` residing in `src/main` | **STILL VALID (P0 Cleanliness)** | **Executed**: Moved all 4 fake adapters and fixtures to `src/test`. |

---

## 5. Stale Recommendations Discovered

1. **Speculative Extraction of Generic Model Providers (`ModelProvider`, `ModelResolver`, `ModelFactory`):**
   - *Audit Suggestion:* Abstract all LLM providers into a dynamic plugin framework.
   - *Status:* **STALE / REJECTED**. Adding a multi-tier provider abstraction layer without a multi-tenant provider switching requirement violates Rule 5 (Abstraction Discipline). The existing `AiModelConfig` and `SmartRoutingService` handle dynamic fallback adequately without speculative indirection.
2. **Decomposition of `StreamingChatEngine` (~800 lines):**
   - *Audit Suggestion:* Break down into multiple sub-engines.
   - *Status:* **DEFERRED (Hotspot Protection)**. The class is an active hotspot. Breaking it down without a concrete responsibility collision trigger risks destabilizing SSE event flow.

---

## 6. Actual P0 Issues Resolved

1. **SQL Backdoor (`executeQuerySql`):** Removed from production tools. Prevents arbitrary SQL execution vulnerabilities by LLM agents.
2. **AHP Recommendation Vulnerability & Timeout Semantics:** Auto-assignment heuristic now safely falls back to local deterministic explanations if the explanation LLM times out or errors. Configured with a bounded 5000ms timeout (`@Value("${ai.assignment.explanation-timeout-ms:5000}")`), truth-bound model telemetry, and explicitly documented caller-blocking and uncancelable background socket behavior.
3. **Model Catalog Audit & Deprecation Repair:**
   - Removed dead GitHub Models beans and endpoints (`models.inference.ai.azure.com`).
   - Replaced retired Groq models (`llama-3.3-70b-versatile`, `meta-llama/llama-4-scout-17b-16e-instruct`, `llama-3.1-8b-instant`) with active Groq models (`openai/gpt-oss-120b`, `openai/gpt-oss-20b`).
   - Replaced shut-down Gemini 2.0 models (`gemini-2.0-flash`, `gemini-2.0-flash-lite`) with active Gemini models (`gemini-3.5-flash-lite`, `gemini-3.8-flash`).
   - Cleaned legacy "Llama 3.3" comments and renamed token limit constants in streaming coordinators.
4. **Production Hygiene:** Fake adapters moved to test scope, preventing stubbed domain logic from ever being packaged in production JARs.
5. **CommentAiTools Architecture Boundary:** Retained bounded parameterized query as a pre-existing domain boundary issue without introducing speculative cross-module abstractions.
6. **Regression Verification:** Full test suite verified green: 148 tests executed, 141 passed, 0 failures, 0 errors, 7 skipped.

---

## 7. Components Intentionally Left Untouched

- `com.taskpilot.ai.rag.*`: RAG storage, ingestion, document chunking, and pgvector retrieval are stable and were left strictly untouched.
- `StreamingChatEngine`: Core lifecycle maintained; only the hardcoded Groq string lookup was updated to use `routingService.getReasoningTextModel()`.
- `ToolCallingRegistryService`: Core dispatch retained; only `executeQuerySql` registration and filters were pruned.
- Domain modules (`taskpilot-projects`, `taskpilot-users`): Unmodified. All contracts and boundary rules preserved.

---

## 8. Remaining Future Triggers

- **Trigger for `StreamingChatEngine` Decomposition:** When tool-calling lifecycle and SSE streaming protocols require concurrent, conflicting changes for new client protocols.
- **Trigger for `SmartRoutingService` Provider Separation:** When more than 3 active inference providers require distinct runtime credential rotation or dynamic tenant routing.
- **Trigger for `PendingAiActionService` Persistence:** When TaskPilot transitions to a multi-instance, clustered deployment requiring durable action tokens across JVM restarts.

---

## 9. Reusable Agent Intelligence & Skill Reconciliation

- **Problem:** Machine-local duplicate folders (`.agent` and `.agents`) existed across developer workspace roots, with inconsistent skill sets (`context7` only in `.agent`, `taskpilot-architecture-maintenance` duplicated in both, and neither tracked in the Git repository).
- **Resolution:** Consolidated all engineering intelligence under the single canonical repository-owned directory `taskpilot/.agents/`.
- **Duplicates Purged:** Deleted machine-local `.agent` directories.
- **Canonical Assets:** `.agents/README.md`, `.agents/skills/taskpilot-architecture-maintenance/SKILL.md`, and `.agents/skills/context7/SKILL.md` are now fully version-controlled in Git alongside the existing 9 skills.

