# TaskPilot RAG Subsystem — Architectural Decisions Record (ADR)

## ADR-01: Canonical Embedding Model and Dimension Selection

- **Context**: The TaskPilot system uses Gemini 3.8 Flash for LLM reasoning and generation. Generation and vector embeddings are distinct concerns. The legacy Google model `text-embedding-004` was deprecated/shutdown by Google on 2026-01-14. RAG requires a supported, canonical vector embedding model with reliable semantic retrieval performance, verified output dimensionality, and direct integration with the project's dependency stack.
- **Decision**: Selected Google's canonical **`gemini-embedding-2`** via `GoogleAiEmbeddingModel` in `dev.langchain4j:langchain4j-google-ai-gemini:1.0.0-beta5`.
- **LLM Generation**: Gemini 3.8 Flash.
- **Embedding Model**: `gemini-embedding-2`. Generation and embedding remain separate concerns.
- **Dimensionality**: **768** dimensions (`vector(768)`). Verified via live API and automated test (`GoogleAiEmbeddingModelVerificationTest`) that Google's `gemini-embedding-2` and LangChain4j 1.0.0-beta5 support Matryoshka dimensionality reduction via `outputDimensionality(768)`, returning exact 768-dimensional vectors.
- **Similarity Metric**: Cosine similarity / pgvector cosine distance (`<=>` operator).
- **Consistency**: Ingestion and retrieval MUST use exactly the same embedding model (`gemini-embedding-2`), dimension (768), task/configuration, and preprocessing.
- **Embedding Failure Handling**: No LLM fallback is allowed. If embedding generation fails, document ingestion transitions to `FAILED` and fails cleanly.

---

## ADR-02: Custom DocumentChunkRepository vs. LangChain4j Generic PgVectorEmbeddingStore

- **Context**: LangChain4j provides a generic `PgVectorEmbeddingStore`. However, TaskPilot has strict multi-tenancy and domain lifecycle requirements:
  - Strong domain entity `documents` with explicit status (`UPLOADING`, `PROCESSING`, `READY`, `FAILED`).
  - Strict project isolation where `project_id` is a mandatory query constraint.
  - Cascading deletion of chunks when a document or project is removed.
  - Custom SQL and HNSW index tuning under Flyway migration control.
- **Decision**: Implement a custom **`DocumentChunkRepository`** using Spring Data JDBC / `JdbcTemplate` with native PostgreSQL pgvector SQL (`CAST(:embedding AS vector)`).
- **Rationale**: Keeps database schema under Flyway control, avoids leaky abstractions, guarantees project-level isolation at the query layer, and enables seamless integration with existing domain transactions.

---

## ADR-03: Denormalized `project_id` on `document_chunks`

- **Context**: Every document chunk belongs to a document, which belongs to a project.
- **Decision**: Store `project_id` directly on the `document_chunks` table alongside `document_id`.
- **Application Invariant**: The application layer derives `document_chunks.project_id` strictly from `documents.project_id`. Client-provided project IDs are never trusted during chunk creation.
- **Rationale**: Denormalizing `project_id` provides a direct filtering predicate at the chunk/vector table and simplifies the authorization/data-isolation boundary.

---

## ADR-04: Storage Reuse and Read Extension

- **Context**: TaskPilot already features `StorageService` and `S3StorageServiceImpl` backed by Supabase S3 via AWS SDK v2 (`software.amazon.awssdk:s3:2.20.160`).
- **Decision**: Extend `StorageService` with `InputStream downloadFile(String key)` in `S3StorageServiceImpl`.
- **Rationale**: Prevents introducing redundant S3 clients or dependencies, maintains unified credential management, and preserves existing avatar/file upload functionality intact.

---

## ADR-05: Document Parsing Strategy with Apache Tika

- **Context**: Project documents uploaded by users will primarily include PDF, DOCX, Markdown, and text files.
- **Decision**: Use Apache Tika (`org.apache.tika`) to parse input streams into clean extracted text.
- **Supported Formats**:
  - `application/pdf` (.pdf)
  - `application/vnd.openxmlformats-officedocument.wordprocessingml.document` (.docx)
  - `text/plain` (.txt, .csv)
  - `text/markdown` (.md)
- **Scope Note**: Apache Tika is capable of detecting and parsing hundreds of file types. The formats listed above represent the active, officially supported and tested document types for TaskPilot RAG. Unsupported or corrupt files will be rejected gracefully with a descriptive error.

---

## ADR-06: Recursive Text Chunking Strategy

- **Context**: Splitting text across arbitrary character boundaries truncates sentences and destroys semantic coherence.
- **Decision**: Utilize recursive text chunking via LangChain4j's `DocumentSplitters.recursive(700, 100)`.
- **Parameters**:
  - Target chunk size: **700 characters** (initial heuristic).
  - Overlap: **100 characters** (initial heuristic).
- **Rationale**: Recursive chunking progressively breaks text at paragraph, newlines, sentences, and word boundaries. 700 chars with 100 char overlap serves as an initial baseline heuristic to be benchmarked and re-tuned as document corpora evolve.

---

## ADR-07: Authorization Boundary & Session Scoping

- **Context**: Chat sessions in TaskPilot are user-scoped (`userId`), not project-scoped.
- **Decision**:
  - Never infer project authorization from chat session ownership alone.
  - Every RAG retrieval via tool `searchProjectKnowledge(projectId, query)` must explicitly validate that the calling user (`ToolExecutionContext.requireUserId()`) is an active member of `projectId` via `ProjectSecurityService` / `ProjectMemberPort`.
  - If the user is not a member, retrieval is rejected with `403 Forbidden` before vector search or query embedding occurs.
- **Rationale**: Rejecting early prevents unauthorized users from querying project data, prevents information leaks, and prevents unnecessary external embedding API calls for unauthorized requests.

---

## ADR-08: Durable PostgreSQL Job Queue & Document Chunk Staging for Resumable Ingestion

- **Context**: Document ingestion involves multiple heavy stages: S3 file download, Apache Tika text extraction, recursive chunking, and batch embedding calls to Google Gemini API. If ingestion of large documents (e.g. 50+ pages, 200+ chunks) fails mid-flight due to network timeouts, server restarts, or provider rate limits (HTTP 429), re-starting from scratch causes repeated S3 reads, redundant CPU-heavy Tika parsing, wasted API quota, and long user wait times.
- **Decision**: Introduce Flyway migration `V27__create_document_chunk_staging.sql` and durable staging table `document_chunk_staging` paired with durable state fields on `documents` (`processing_version`, `lease_until`, `retry_count`, `next_attempt_at`).
- **Core Mechanism**:
  1. During ingestion step 1, text chunks are parsed and persisted *upfront* into `document_chunk_staging` with `embedding IS NULL`.
  2. Embeddings are generated in batches (size $\le 20$) and committed immediately to staging as each batch completes (`UPDATE document_chunk_staging SET embedding = CAST(:embedding AS vector) WHERE id = :id`).
  3. Staging separates active unverified work from the published vector index (`document_chunks`). Only after 100% of chunks in staging have non-null embeddings does the system atomically copy chunks into `document_chunks` inside a row-locked transaction (`SELECT ... FOR UPDATE` on `documents`) and mark the document `READY`.
- **Rationale**: Eliminates duplicate S3 downloads and Tika parsing on retries; guarantees zero partial/corrupted chunk visibility in RAG search.

---

## ADR-09: Dual-Dimension Quota Admission (100 RPM, 30,000 TPM) with Protected Interactive Headroom and Sliding-Window Normal Pacing

- **Context**: Google Gemini API free-tier embedding (`gemini-embedding-2`) imposes strict quota limits: 100 Requests Per Minute (RPM) and 30,000 Tokens Per Minute (TPM). Both background ingestion workers and real-time interactive user searches (`searchProjectKnowledge`, chat copilot) query the same embedding endpoint and share this quota pool.
- **Decision**: Centralize all embedding calls through `EmbeddingGateway` backed by `RpmRateLimiter` implementing a sliding-window token and request tracking mechanism.
- **Parameters**:
  - `maxRpm`: 100 requests/minute.
  - `maxTpm`: 30,000 tokens/minute.
  - `interactiveHeadroomRpm`: 10 requests reserved exclusively for interactive user queries.
  - `interactiveHeadroomTpm`: 3,000 tokens reserved exclusively for interactive user queries.
  - `pacingWaitMs`: 5,000 ms.
- **Distinction between Normal Pacing and RETRY_WAIT**:
  - *Normal Pacing*: If background ingestion needs admission and the window is full, the thread suspends on `Condition.await()` up to `pacingWaitMs` without changing document status. Once the sliding window clears, the worker proceeds smoothly.
  - *RETRY_WAIT*: If pacing times out or provider returns 429/timeout, the worker relinquishes its thread, transitions document to `RETRY_WAIT`, and schedules `next_attempt_at` with exponential backoff (e.g. 60s) via `DocumentJobPoller`.
- **Rationale**: Completely prevents interactive chat search starvation during massive background ingestion; prevents provider 429 spikes; avoids worker thread exhaustion.

---

## ADR-10: Provider Quota Chunk Accounting (Gemini Free Tier 100 Requests Limit)

- **Context**: During batch embedding with Google's `batchEmbedContents`, Google treats each individual text chunk inside the batch request payload as 1 request against the `embed_content_free_tier_requests` quota (100 RPM limit). Submitting a single batch of 212 chunks in one HTTP request immediately triggers a 429 `RESOURCE_EXHAUSTED` error despite being a single HTTP call.
- **Decision**:
  1. `RpmRateLimiter` tracks request count in its sliding window as `requestCount = batch.size()` (number of segments), rather than counting the batch HTTP request as 1.
  2. Batch size is capped at `maxBatchSize = 20` segments per batch call.
- **Rationale**: Accurately reflects provider quota consumption; ensures the sliding window never admits a batch that would exceed the 100 RPM limit.

---

## ADR-11: Staging Chunk Adoption Across Processing Version Increments (`adoptOlderStagedChunks`)

- **Context**: When a document hits a pacing timeout or provider quota delay and transitions to `RETRY_WAIT`, its lease expires. When claimed next by `DocumentJobClaimer`, `processing_version` is atomically incremented (e.g., from version 1 to version 2) to fence out zombie workers. However, version 1 already parsed the text and may have embedded 20, 40, or 60 chunks into `document_chunk_staging`.
- **Decision**: When claiming a document with existing staged records, the worker runs `adoptOlderStagedChunks(documentId, claimedVersion)`:
  `UPDATE document_chunk_staging SET processing_version = :claimedVersion WHERE document_id = :documentId AND processing_version < :claimedVersion`.
- **Rationale**: S3 download and Tika parsing run exactly once. All already-computed vector embeddings in staging survive across worker versions, and only chunks where `embedding IS NULL` are dispatched to Gemini. Large documents (e.g. 200+ chunks) deterministically converge to completion across scheduled retry windows.

---

## ADR-12: Role-Based Access Control (RBAC) on Project Knowledge Base

- **Context**: Allowing any project member to upload, re-index, and delete documents can result in cluttered or corrupted knowledge bases, accidental deletion of critical specifications, and uncoordinated quota consumption. However, all project members must be able to benefit from the knowledge base for semantic search, task context, and AI assistance.
- **Decision**:
  - **Project Manager (`MANAGER`)**: Has write/mutation authority:
    - Upload new documents (`POST /api/projects/{projectId}/documents`).
    - Retry/re-index documents (`POST /api/projects/{projectId}/documents/{documentId}/retry`).
    - Delete documents (`DELETE /api/projects/{projectId}/documents/{documentId}`).
  - **Project Member (`MEMBER`)**: Has read/query authority:
    - View document list and status (`GET /api/projects/{projectId}/documents`).
    - Inspect document details (`GET /api/projects/{projectId}/documents/{documentId}`).
    - Execute semantic RAG search (`POST /api/projects/{projectId}/documents/search`).
    - Trigger AI Copilot knowledge retrieval tool (`searchProjectKnowledge(projectId, query)`).
  - **Non-Members**: Denied all access with `403 Forbidden`.
- **Frontend Enforcement**:
  - In `ProjectKnowledgeTab`, if the user has `MEMBER` role:
    - The drag-and-drop upload card is hidden and replaced with an informative banner informing the user that document uploads are restricted to Project Managers.
    - Delete and Retry buttons are hidden from the document list items.
  - In `ProjectKnowledgeTab`, if the user has `MANAGER` role:
    - Full upload dropzone, retry buttons, and delete buttons are visible and functional.
- **Backend Enforcement**:
  - `ProjectDocumentController` endpoints for upload, retry, and delete enforce manager authorization via `projectSecurityService.requireProjectManager(projectId, userId)`.
  - Read and search endpoints enforce member authorization via `projectSecurityService.requireProjectMember(projectId, userId)`.
- **Rationale**: Protects knowledge base integrity from accidental modifications or spamming while empowering all members to leverage RAG search; provides defense-in-depth with frontend UI gating and strict backend security checks.
