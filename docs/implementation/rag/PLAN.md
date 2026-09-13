# TaskPilot RAG Subsystem — Execution Plan

This document tracks the step-by-step implementation, checkpoints, and verification of the project-scoped RAG subsystem in TaskPilot.

## Execution Phases

- [x] **Phase 0 — Repository inspection**
  - Inspected repo multi-module structure (`taskpilot-app`, `taskpilot-ai`, `taskpilot-projects`, `taskpilot-users`, `taskpilot-infrastructure`, `taskpilot-contracts`).
  - Verified Java 25 LTS, Spring Boot 4.1.0, LangChain4j 1.0.0 / 1.0.0-beta5, Flyway 11.3.2.
  - Inspected existing storage (`StorageService`, `S3StorageServiceImpl`), AI tools (`TaskPilotAiTools`), security (`ProjectSecurityService`, `ProjectMemberRepository`).

- [x] **Phase 1 — Baseline verification**
  - Executed full test suite via `.\mvnw.cmd test`.
  - All 47 tests passed (37 AI, 10 Projects). Baseline recorded in `STATUS.md`.

- [x] **Phase 2 — Persistent Skill & documentation**
  - Created `.agents/skills/taskpilot-rag/SKILL.md`
  - Created references: `architecture.md`, `decisions.md`, `verification.md`
  - Created scripts directory and README: `.agents/skills/taskpilot-rag/scripts/README.md`
  - Initialized and refined `docs/implementation/rag/PLAN.md`, `STATUS.md`, `DECISIONS.md` (ADR-01 through ADR-07), `VERIFICATION.md`.

- [x] **Phase 3 — Verify embedding model & API**
  - Verified that `text-embedding-004` is shutdown (404 NOT_FOUND from Google API).
  - Selected canonical model: **`gemini-embedding-2`**.
  - Verified live via `GoogleAiEmbeddingModelVerificationTest` and REST API that `gemini-embedding-2` configured with `outputDimensionality = 768` produces exact 768-dimensional vectors using `GoogleAiEmbeddingModel` in LangChain4j 1.0.0-beta5.
  - Documented canonical model selection, dimension, and cosine distance metric in `DECISIONS.md`.

- [x] **Phase 4 — Database migration**
  - Created and executed Flyway migration `V22__create_rag_tables.sql` on PostgreSQL 17.6 with pgvector.
  - Successfully applied: `vector` extension, `documents` table, `document_chunks` table with `vector(768)`.
  - Enforced foreign key cascades, tenant B-tree index on `project_id`, and HNSW index on `embedding` with `vector_cosine_ops` (`m = 16, ef_construction = 64`).
  - Verified via `FlywayMigrationVerificationTest` (BUILD SUCCESS).

- [x] **Phase 5 — Storage read capability**
  - Extended `StorageService` interface with `InputStream downloadFile(String fileUrlOrKey) throws IOException`.
  - Implemented `downloadFile` and completed `deleteFile` in `S3StorageServiceImpl` using AWS SDK v2 `s3Client.getObject(...)` and `s3Client.deleteObject(...)`.
  - Added URL prefix stripping helper to support both full URLs and raw S3 storage keys.
  - Verified via `S3StorageServiceImplTest` (4/4 tests pass).

- [ ] **Phase 6 — Document domain & persistence**
  - Implement `DocumentEntity`, `DocumentStatus` enum, `DocumentChunkEntity`.
  - Implement Spring Data JPA repository for documents.

- [ ] **Phase 7 — Apache Tika parsing**
  - Add Apache Tika dependency or integrate text parsing.
  - Implement `DocumentTextExtractor` for PDF, DOCX, TXT, MD, CSV.
  - Document supported/unsupported MIME types.

- [ ] **Phase 8 — Recursive text chunking**
  - Implement `DocumentChunker` using LangChain4j's `DocumentSplitters.recursive(700, 100)`.
  - Support natural text boundary chunking with initial heuristic ~700 char chunks and ~100 char overlap.

- [ ] **Phase 9 — Canonical embedding service**
  - Implement `EmbeddingService` interface and `GoogleAiEmbeddingServiceImpl`.
  - Enforce canonical model configuration (`gemini-embedding-2`, 768 dimensions).
  - Provide both single text and batch embedding generation with dimension validation.

- [ ] **Phase 10 — Vector repository**
  - Implement `DocumentChunkRepository` using JDBC/native PostgreSQL SQL for pgvector.
  - Enforce strict `project_id = :projectId` filtering at query level.
  - Support top-K nearest chunk retrieval ordered by cosine distance (`embedding <=> CAST(:queryVector AS vector)`).
  - Support chunk deletion for document lifecycle cleanup.

- [ ] **Phase 11 — Ingestion pipeline**
  - Implement `DocumentIngestionService` coordinating:
    `S3 download -> Tika extraction -> Chunking -> Embedding -> Transactional chunk persistence -> READY/FAILED status transition`.
  - Ensure failed ingestion never leaves orphan chunks or inconsistent state.

- [ ] **Phase 12 — Project-scoped retrieval service**
  - Implement `ProjectKnowledgeService` exposing `searchKnowledge(Long projectId, String query, int limit)`.
  - Coordinate query embedding generation and vector similarity search.

- [ ] **Phase 13 — Authorization boundary**
  - Enforce server-side authorization before retrieval: validate user membership in `projectId` using `ProjectSecurityService` / `ProjectMemberPort`.
  - Reject queries with `403 Forbidden` before vector search or query embedding occurs if user is not a project member, regardless of LLM arguments.

- [ ] **Phase 14 — AI tool integration**
  - Add `@Tool searchProjectKnowledge(String projectId, String query)` to `TaskPilotAiTools` (or a dedicated `KnowledgeAiTools` domain component).
  - Extract authenticated `userId` from `ToolExecutionContext.requireUserId()`.
  - Ensure `TaskPilotAiTools` remains a thin adapter delegating to `ProjectKnowledgeService`.

- [ ] **Phase 15 — Unit tests**
  - Unit tests for `DocumentChunker`, `EmbeddingService`, `DocumentIngestionService`, `ProjectKnowledgeService`, and AI tool adapter.

- [ ] **Phase 16 — Integration tests**
  - Verification of Flyway migration, document insertion, chunk persistence, and vector query execution.

- [ ] **Phase 17 — Security & project isolation tests**
  - Verify User A (in Project A) cannot retrieve knowledge from Project B even if Project B has matching embeddings.
  - Verify unauthenticated or non-member requests are rejected with 403 Forbidden.

- [ ] **Phase 18 — Full regression tests**
  - Re-run all existing tests in `taskpilot-ai`, `taskpilot-projects`, `taskpilot-infrastructure`.
  - Ensure zero regressions in existing chat engine, streaming, heuristics, tools.

- [ ] **Phase 19 — Verification harness & scripts**
  - Implement verification scripts in `.agents/skills/taskpilot-rag/scripts/`.

- [ ] **Phase 20 — Final handoff & documentation**
  - Final updates to `STATUS.md`, `PLAN.md`, `DECISIONS.md`, `VERIFICATION.md`, and `SKILL.md`.
