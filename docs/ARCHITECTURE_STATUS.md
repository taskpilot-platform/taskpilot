# TaskPilot Architecture Status

**Last Updated:** September 2026  
**Active Branch:** `refactor`  
**Monolith Status:** Modular Monolith (ADR-001 Compliant)

---

## 1. Architectural Overview

TaskPilot operates as a single-process Spring Boot modular monolith.
- **Cross-module Boundary:** Domain access is mediated strictly by ports and DTOs in `taskpilot-contracts`.
- **AI Module Independence:** `taskpilot-ai` manages its own internal persistence (`ai_logs`, `chat_sessions`, `chat_messages`, `documents`, `document_chunks`) and never directly imports domain entities from `taskpilot-projects` or `taskpilot-users`.
- **Infrastructure Footprint:** Zero microservices, zero Kafka/RabbitMQ brokers, zero distributed cache/lock complexity. Tool execution and agent workflows run entirely in-process.

---

## 2. Stable Boundaries

| Boundary | State | Description |
| :--- | :--- | :--- |
| `taskpilot-contracts` | **Stable** | Standardized SPI layer for all domain operations. |
| `com.taskpilot.ai.rag.*` | **Stable (DONE)** | Complete pgvector, document ingestion, rate-limited embedding gateway, lease fencing, and tenant-isolated retrieval. |
| `com.taskpilot.ai.tools.domain.*` | **Stable** | Domain-specific tool clusters (`ProjectAiTools`, `TaskAiTools`, `SprintAiTools`, `AhpAssignmentAiTools`, `NotificationAiTools`, `SkillAiTools`, `CommentAiTools`, `SystemAiTools`). |
| `com.taskpilot.ai.service.PendingAiActionService` | **Stable** | In-memory human-in-the-loop action coordination. |

---

## 3. Known Hotspots

| Component | Nature | Maintenance Policy |
| :--- | :--- | :--- |
| `StreamingChatEngine` | SSE multi-turn lifecycle orchestrator | Protected. Do NOT add new responsibilities. Only bug fixes and compatibility updates permitted. |
| `SmartRoutingService` | LLM model selection and fallback waterfall | Protected. Routes between primary and fallback models. Speculative provider framework deferred. |
| `ToolCallingRegistryService` | Central tool registration & reflection cache | Protected. Central tool index. Decomposition deferred until tool competition trigger is met. |

---

## 4. Current Model & Provider State

Following the September 2026 model catalog cleanup and targeted correction pass:

| Role | Active Provider | Model Identifier | Status |
| :--- | :--- | :--- | :--- |
| **Primary Streaming & Reasoning** | Gemini | `gemini-3.5-flash` / `gemini-2.5-flash` / `gemini-2.5-pro` | Operational |
| **Groq Reasoning & Communicator** | Groq | `openai/gpt-oss-120b` | Operational |
| **Groq Gatekeeper** | Groq | `openai/gpt-oss-20b` | Operational |
| **Embeddings** | Gemini | `gemini-embedding-2` (768-dim) | Operational |
| **GitHub Models (`models.inference.ai.azure.com`)** | GitHub | Decommissioned | **REMOVED** (HTTP 410 Gone) |
| **Groq Retired Model (`llama-3.3-70b-versatile`)** | Groq | Retired | **REMOVED** (HTTP 404 Not Found) |
| **Groq Deprecated (`llama-4-scout`, `llama-3.1-8b`)** | Groq | Deprecated (Jul/Aug 2026) | **REMOVED** (Replaced by `gpt-oss-120b` & `gpt-oss-20b`) |
| **Gemini Retired (`gemini-2.0-flash/-lite`, `gemini-1.5-pro`)** | Gemini | Deprecated / Shut Down (Jun 2026) | **REMOVED** (Replaced by active 3.x/2.5 series) |

---

## 5. Completed P0 Fixes (September 2026)

- [x] **SQL Backdoor Removal:** Removed `executeQuerySql` from `SystemAiTools`, `TaskPilotAiTools`, and `ToolCallingRegistryService`.
- [x] **AHP Heuristic Decoupling:** Decoupled candidate scoring from LLM availability in `AutoAssignmentService`. Configurable 5000ms bounded timeout (`ai.assignment.explanation-timeout-ms:5000`) and deterministic local fallback. Truthful model telemetry recording.
- [x] **Model Catalog Cleanup:** Purged defunct GitHub inference endpoints, dead Gemini 2.0 endpoints, and retired Groq models (`llama-3.3-70b-versatile`, `meta-llama/llama-4-scout-17b-16e-instruct`, `llama-3.1-8b-instant`). Replaced hardcoded references with dynamic routing.
- [x] **Fake Adapter Isolation:** Moved `com.taskpilot.ai.adapter.fake.*` to `src/test/java`. Production package is clean.
- [x] **Full Regression Verification:** 148 tests executed, 141 passed, 0 failures, 0 errors, 7 skipped in `taskpilot-ai`.

---

## 6. Deferred Refactors & Trigger Conditions

| Candidate Refactor | Current Decision | Concrete Trigger Condition |
| :--- | :--- | :--- |
| **`StreamingChatEngine` Decomposition** | Deferred | Triggered when SSE protocol and tool lifecycle require simultaneous conflicting changes for a new streaming protocol. |
| **Provider Plugin Architecture** | Deferred | Triggered when more than 3 active inference providers require distinct dynamic runtime credential rotation. |
| **`ToolCallingRegistryService` Splitting** | Deferred | Triggered when tool groups require distinct security scopes or compete over execution interceptors. |
| **Durable `PendingAiActionService`** | Deferred | Triggered when deploying multi-instance TaskPilot behind a load balancer requiring cross-JVM action confirmation. |

---

## 7. Reusable Agent Intelligence & Knowledge Consolidation (September 2026)

- **Canonical Knowledge Root:** Consolidated all reusable engineering skills, agent instructions, prompts, and playbooks under `taskpilot/.agents/`.
- **Duplicate Reconciliation:** Reconciled duplicate `.agent` (singular) and `.agents` (plural) copies. Purged obsolete machine-local `.agent` directories.
- **Canonical Architecture Skill:** Established single canonical copy of generic architecture maintenance workflows at `.agents/skills/taskpilot-architecture-maintenance/SKILL.md`.
- **Context7 & Catalog Portability:** Imported `context7` library documentation skill into canonical `.agents/skills/context7/`. Created `.agents/README.md` defining discovery protocols and zero-duplicate rules.

