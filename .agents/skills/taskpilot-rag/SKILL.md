---
name: taskpilot-rag
description: Comprehensive workflow guide and rules for developing, maintaining, and verifying the project-scoped RAG (Retrieval-Augmented Generation) subsystem in TaskPilot using PostgreSQL pgvector, LangChain4j, Apache Tika, and Gemini embedding models.
---

# TaskPilot RAG Subsystem Skill

## 1. Overview & Architecture Rules

TaskPilot RAG empowers the AI assistant to ground its reasoning on project-specific documents (specifications, requirements, tickets, documentation) while maintaining strict tenant isolation.

### 1.1 Core Flow
```text
User
  ↓
Existing AI / StreamingChatEngine (LLM: Gemini 3.8 Flash)
  ↓
TaskPilotAiTools (searchProjectKnowledge)
  ↓
ProjectKnowledgeService
  ↓
Authorization (validateMember - rejected with 403 before vector search or query embedding)
  ↓
EmbeddingService (canonical gemini-embedding-2 with outputDimensionality=768)
  ↓
DocumentChunkRepository
  ↓
PostgreSQL + pgvector (cosine distance HNSW)
  ↓
Relevant project-scoped chunks
  ↓
Tool result
  ↓
Existing LLM (Gemini 3.8 Flash)
```

### 1.2 Non-Negotiable Architecture Rules
1. **Project-Scoped Retrieval**: Every query and document chunk is explicitly bounded by `project_id`. Global searches without project scoping are strictly forbidden.
2. **Authorization Before Retrieval**: Always validate that the authenticated user (`ToolExecutionContext.requireUserId()`) is an active member of `projectId` prior to executing any embedding or vector search. Reject with 403 before external embedding calls occur.
3. **Session Independence**: Never infer project access from chat session ownership alone. Chat sessions are user-scoped; project authorization must be checked explicitly per request.
4. **Canonical Embedding Model**: Ingestion and retrieval must use the exact same canonical embedding model and configuration (`gemini-embedding-2`, 768 dimensions). Never use LLM fallback models for embedding.
5. **Document Lifecycle**: Documents must track state (`UPLOADING`, `PROCESSING`, `READY`, `FAILED`). Ingestion errors must set status to `FAILED` and clean up partial chunks.
6. **Preserve Existing AI Infrastructure**: Do not modify `StreamingChatEngine`, `SmartRoutingService`, `TimeoutFallbackHandler`, or model fallback mechanisms. RAG is exposed cleanly through the existing tool architecture.

---

## 2. Coding Rules

1. **Inspect Before Modifying**: Never write code assuming outdated dependencies or abstractions. Always check current pom.xml, entities, repositories, and ports.
2. **Reuse Existing Storage**: Use `StorageService` / `S3StorageServiceImpl` backed by AWS SDK v2. Extend with `InputStream downloadFile(String key)`. Do not add secondary S3 SDKs.
3. **Reuse Existing Security**: Reuse `ProjectSecurityService` / `ProjectMemberPort` for validating project membership.
4. **Reuse LangChain4j Version**: Use the project's pinned LangChain4j version (`1.0.0` core / `1.0.0-beta5` integrations). Verify exact API compatibility before writing integration code.
5. **No Unnecessary Dependencies**: Keep external additions focused (e.g. Apache Tika for parsing). Avoid adding heavyweight frameworks.
6. **No Leaky Tool Implementations**: Keep `TaskPilotAiTools` a thin delegation layer. Business logic, parsing, chunking, and SQL belong in domain services and repositories.

---

## 3. Database & pgvector Rules

1. **Flyway Migrations**: All schema modifications must use versioned Flyway migration scripts (e.g., `V22__create_rag_tables.sql`).
2. **Schema Invariant**: `document_chunks.project_id` must match `documents.project_id`. The application derives this strictly from the parent document.
3. **Vector Dimension**: `vector(768)` corresponding to Google's canonical `gemini-embedding-2` configured with `outputDimensionality = 768`.
4. **Indexes**:
   - B-tree index on `project_id` for fast tenant filtering.
   - HNSW index on `embedding` with `vector_cosine_ops` (`m = 16, ef_construction = 64`) for fast approximate nearest neighbor search.
5. **Custom Repository over Generic Store**: Implement `DocumentChunkRepository` using native PostgreSQL JDBC/SQL (`CAST(:queryVector AS vector)` and cosine distance operator `<=>`) to preserve domain isolation and transaction consistency.

---

## 4. Verification Rules

1. **Execute Every Step**: **Never claim a verification step passed unless it was actually executed.**
2. **Standard Checkpoints**:
   - Compile/build: `.\mvnw.cmd test-compile`
   - Unit tests: `.\mvnw.cmd test -pl taskpilot-ai`
   - Security tests: Verify 403 Forbidden for non-members and cross-project queries.
   - Full regression: `.\mvnw.cmd test`
3. **Persistent Records**: Update `docs/implementation/rag/STATUS.md` and `docs/implementation/rag/VERIFICATION.md` after every phase with exact commands, timestamps, and outputs.
