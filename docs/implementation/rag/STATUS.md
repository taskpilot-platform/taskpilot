# TaskPilot RAG Subsystem — Implementation Status

## Current State Summary
- **Current phase**: Complete (All Phases 0–19 fully implemented and verified)
- **Completed**:
  - Phase 0 — Repository inspection
  - Phase 1 — Baseline verification
  - Phase 2 — Persistent Skill & Documentation
  - Phase 3 — Verify embedding model & API (`gemini-embedding-2` with `outputDimensionality = 768` verified live)
  - Phase 4 — Database migration (`V22__create_rag_tables.sql` applied with pgvector, `vector(768)`, HNSW cosine index)
  - Phase 5 — Storage read capability (`StorageService.downloadFile` and `deleteFile` implemented in `S3StorageServiceImpl`)
  - Phase 6 — Document domain & persistence (`DocumentStatus`, `DocumentChunk`, `ScoredChunk`, `DocumentEntity`, `DocumentRepository`, `ProjectMemberPort.isProjectMember`)
  - Phase 7 — Apache Tika document text extraction (`DocumentTextExtractor`, `TikaDocumentTextExtractor`)
  - Phase 8 — Recursive text chunking (`DocumentChunker`, `LangChain4jDocumentChunker` 700/100)
  - Phase 9 — Canonical embedding service (`EmbeddingService`, `GoogleAiEmbeddingServiceImpl`)
  - Phase 10 — Vector repository (`DocumentChunkRepository`, `JdbcDocumentChunkRepository` with pgvector `<=>` cosine distance)
  - Phase 11 — Ingestion pipeline (`DocumentIngestionService`, `DocumentIngestionServiceImpl`)
  - Phase 12 — Project knowledge retrieval (`ProjectKnowledgeService`, `ProjectKnowledgeServiceImpl` with strict tenant isolation)
  - Phase 13 & 14 — AI Tool integration (`KnowledgeAiTools`, `TaskPilotAiTools`, `ToolCallingRegistryService` exposing `searchProjectKnowledge`)
  - Phase 15 — Controller & REST API (`ProjectDocumentController`, `ProjectDocumentService`, multipart S3 upload, async indexing, document lifecycle, re-indexing, knowledge search)
  - Phase 16 — Production Hardening & E2E Conversational Verification (Non-blocking transaction boundaries, 15m crash recovery with safe retry, and LangChain4j conversational tool flow integration test)
  - Phase 17 — Frontend UI & Browser UAT (`ProjectKnowledgeTab`, `DocumentUploadCard`, `DocumentList`, `KnowledgeSearchCard`, `KnowledgeHeader`, vitest 17/17 tests passing, manual UAT checklist)
  - Phase 18 — Resumable Ingestion & Quota-Aware Embedding:
    - PostgreSQL durable job queue (`DocumentJobClaimer`, `DocumentJobPoller`, atomic leasing via `FOR UPDATE SKIP LOCKED`).
    - Staging table separation (`document_chunk_staging`, Flyway `V27`).
    - Upfront text chunk persistence, version fencing (`processing_version`), and staged chunk adoption (`adoptOlderStagedChunks`).
    - Dual-dimension rate limiting (`RpmRateLimiter`: 100 RPM, 30,000 TPM with sliding-window normal pacing and protected interactive headroom).
    - Large document stress testing: 212-chunk DOCX survived 429 quota exhaustion with 0 chunk loss and achieved `READY` status.
  - Phase 19 — Role-Based Access Control (RBAC) on Project Knowledge Base:
    - Backend security gates in `ProjectDocumentController`: Manager-only for upload, retry, and delete (`requireProjectManager`); Member-permitted for document listing, detail inspection, and semantic search (`requireProjectMember`).
    - Frontend adaptive UI in `ProjectKnowledgeTab`: Managers receive full upload card and delete/retry buttons; Members receive read-only banner explaining manager restriction with delete/retry actions hidden.
    - Automated tests: 120/120 backend tests passing in `taskpilot-ai`, 10/10 in `taskpilot-projects`, 23/23 frontend tests passing in `taskpilot-frontend`.
    - Live multi-user verification with Puppeteer screenshots (`rbac_1_member_knowledge_view.png`, `rbac_2_manager_knowledge_view.png`).
  - Secret Audit — 100% CLEAN: All 14 secret keys strictly read from environment/.env; 0 leaks across repository
- **In progress**: None
- **Blocked**: None
- **Next action**: Final reporting documentation & project submission.
- **Last verified command**: `npm run build && npm test` (Frontend: 23/23 passed) & `.\mvnw.cmd test` (Backend: 120/120 passed in taskpilot-ai)
- **Last verification result**: BUILD SUCCESS (Frontend: 23/23 passed, Backend: 120/120 passed)

---


## Baseline Verification Record
- **Date/Time**: 2026-09-05 21:40:00 +07:00
- **Git Branch**: `feat/rag-storage`
- **Commit Hash**: `7dae17df9e7ff8dcf33c328e076e1880ecf4f4db`
- **Java Version**: OpenJDK 25.0.2 LTS (Temurin-25.0.2+10)
- **Maven Version**: Apache Maven 3.9.11
- **Spring Boot Version**: 4.1.0
- **LangChain4j Core Version**: 1.0.0
- **LangChain4j Modules Version**: 1.0.0-beta5
- **Flyway Version**: 11.3.2
- **Database**: PostgreSQL on Supabase (`aws-1-ap-southeast-1.pooler.supabase.com:6543/postgres`)
- **Build Command**: `.\mvnw.cmd -DskipTests clean compile`
- **Test Command**: `.\mvnw.cmd test`
- **Test Results**:
  - `taskpilot-infrastructure`: SUCCESS (4 tests)
  - `taskpilot-contracts`: SUCCESS
  - `taskpilot-users`: SUCCESS
  - `taskpilot-ai`: SUCCESS (39 tests)
  - `taskpilot-projects`: SUCCESS (10 tests)
  - `taskpilot-app`: SUCCESS (1 test)
  - Total: 54 tests, 0 failures, 0 errors, 0 skipped.
- **Existing Failures Unrelated to RAG**: None. The baseline is 100% clean and passing.
