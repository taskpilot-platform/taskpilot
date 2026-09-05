# TaskPilot RAG Key Architectural Decisions Quick Reference

For full detail, refer to `docs/implementation/rag/DECISIONS.md`.

1. **Canonical Embedding Model**:
   - Model: `gemini-embedding-2` (Google AI Gemini API).
   - Dimensions: 768 (`vector(768)`).
   - Distance Metric: Cosine Distance (`<=>`).
   - Consistency Rule: Ingestion and query embedding must use the exact same model. No LLM fallbacks allowed for embeddings.
   - Verification: Verified live with `GoogleAiEmbeddingModelVerificationTest` that `outputDimensionality = 768` returns 768-dim vectors.

2. **Database Schema & Multi-Tenancy**:
   - PostgreSQL 15+ with `pgvector` extension.
   - Tables: `documents` and `document_chunks`.
   - `project_id` is denormalized directly on `document_chunks` and strictly set by the application layer from the parent document.
   - Rationale: Provides a direct filtering predicate at the chunk/vector table and simplifies the authorization/data-isolation boundary.
   - Indexing: B-tree on `project_id` + HNSW index on `embedding` with `vector_cosine_ops`.

3. **Repository Pattern**:
   - Custom `DocumentChunkRepository` using JDBC/native PostgreSQL SQL.
   - Avoid generic `PgVectorEmbeddingStore` to preserve domain models, transaction lifecycles, and direct tenant isolation.

4. **Storage Reuse**:
   - Extend existing `StorageService` / `S3StorageServiceImpl` with `InputStream downloadFile(String key)` using existing AWS SDK v2 client.

5. **Authorization Boundary**:
   - Always validate `ProjectSecurityService.validateMember(projectId, userId)` before executing retrieval or document management.
   - Reject with `403 Forbidden` before vector search or query embedding occurs.
   - Chat sessions are user-scoped; never assume project authorization from chat ownership.

6. **Text Processing**:
   - Extraction: Apache Tika for PDF, DOCX, TXT, MD, CSV.
   - Chunking: Recursive text chunking using `DocumentSplitters.recursive(700, 100)` (700 characters max, 100 characters overlap) as an initial baseline heuristic.
