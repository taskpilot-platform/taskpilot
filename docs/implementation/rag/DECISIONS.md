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
