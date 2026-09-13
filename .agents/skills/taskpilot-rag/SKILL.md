---
name: taskpilot-rag
description: Comprehensive workflow guide and rules for developing, maintaining, and verifying the project-scoped RAG (Retrieval-Augmented Generation) subsystem in TaskPilot using PostgreSQL pgvector, LangChain4j, Apache Tika, and Gemini embedding models.
---

# TaskPilot RAG Subsystem Skill

## 1. Overview & Purpose

TaskPilot RAG empowers the AI assistant to ground its reasoning on project-specific documents (specifications, requirements, tickets, documentation) while maintaining strict tenant isolation, quota safety, and ingestion reliability.

### When to Use This Skill
- Working on document ingestion, parsing (Apache Tika), chunking, or embedding.
- Modifying vector search, nearest-neighbor retrieval, or pgvector indexes.
- Tuning or debugging rate limiting, embedding quota admission, or Gemini API limits.
- Managing document states, queue claiming, lease fencing, or worker concurrency.
- Verifying end-to-end RAG flows, security boundaries, or tenant data isolation.

---

## 2. High-Level Architecture & Invariants

### 2.1 Modular Monolith Constraint
- **Strict Invariant**: TaskPilot is a modular monolith. Do NOT introduce Kafka, RabbitMQ, Redis, external distributed locks, or microservice architectures.
- PostgreSQL serves as the single durable queue, lease coordinator, staging area, and vector store.

### 2.2 Core Ingestion Flow
```text
Client Upload -> S3 Storage -> DocumentEntity(QUEUED) -> DB Queue Commit -> HTTP 201
                                                                    │
Scheduled DocumentJobPoller (every 2s) ◄────────────────────────────┘
         │
DocumentJobClaimer (SELECT FOR UPDATE SKIP LOCKED, version++, lease_until, PROCESSING)
         │
DocumentIngestionService:
  1. Check staging: if empty, download from S3, extract text (Apache Tika), chunk deterministically
  2. Persist all text chunks into document_chunk_staging (embedding = NULL)
  3. Query pending chunks: SELECT WHERE embedding IS NULL (enables zero-loss resumability)
  4. Embed in batches (<= 100) via EmbeddingGateway (RPM + TPM sliding window + normal pacing)
  5. Update embeddings in document_chunk_staging per batch
  6. Fenced Atomic Finalization (SELECT FOR UPDATE, verify version, copy staging to document_chunks, mark READY)
  7. Post-commit cleanup of staging records
```

### 2.3 Non-Negotiable Invariants
1. **Tenant Isolation**: Every document chunk and vector query is bounded strictly by `project_id`. Global cross-project vector search is forbidden.
2. **Authorization Gate**: Authenticated user (`userId`) membership in `projectId` must be verified via `ProjectMemberPort` before executing any external embedding API call or vector search (reject with 403).
3. **Canonical 768-Dim Embedding**: Both ingestion and search must use canonical `gemini-embedding-2` configured with `outputDimensionality = 768`. Internal SDK retries are disabled (`maxRetries = 0`) to preserve application-level quota control.
4. **Single Ingestion/Query Choke Point**: All embedding traffic passes through `EmbeddingGateway`, sharing rate and token quota admission (`RpmRateLimiter`).
5. **Interactive Headroom Protection**: Interactive chat searches have priority and dedicated quota headroom (`interactiveHeadroom`, `interactiveTpmHeadroom`). Background ingestion cannot starve interactive queries.
6. **Zero Zombie Corruption**: Every processing attempt increments `processing_version`. All database mutations, failure transitions, and atomic publications are fenced by `processing_version = claimedVersion`.
7. **Staging Before Publishing**: Chunk text is persisted in `document_chunk_staging` before calling embedding APIs. Partial embedding progress survives crashes. `document_chunks` is only updated via atomic publication when all chunks are embedded.
8. **Normal Pacing vs RETRY_WAIT**: Quota pacing waits inside the worker up to `pacingWaitMs`. Only genuine provider throttles (429, timeouts, quota exhaustion) yield the worker to `RETRY_WAIT` with exponential backoff.

---

## 3. Engineering & Verification Workflow

1. **Inspect Before Changing**: Check Flyway migrations, entities, repositories, and dependency versions before modifying code.
2. **Database Migrations**: Add incremental, forward-only Flyway migrations (e.g., `V26__add_document_queue_support.sql`, `V27__create_document_chunk_staging.sql`). Never alter applied migrations.
3. **Execution-First Verification**: Never claim a test passed without running it. Run focused tests, then module tests, then full regression suite.
4. **Preserve Skill Consistency**: Update `references/architecture.md`, `references/decisions.md`, and `references/verification.md` whenever the system evolves.

---

## 4. References to Detailed Documentation
- [Architecture Reference](references/architecture.md): Topology, state machine, job queue, leasing, fencing, staging, admission limiter, and atomic publication.
- [Key Decisions Reference](references/decisions.md): Architectural decisions and rationales.
- [Verification Protocol](references/verification.md): Exact test suites, database invariants, and verification commands.
