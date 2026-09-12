# TaskPilot RAG Architectural Decisions

This document records the finalized architectural decisions for TaskPilot RAG Ingestion and Retrieval.

---

### Decision 1: TaskPilot Remains a Modular Monolith
- **Context**: Document ingestion requires reliable queues, leasing, retries, and rate limiting.
- **Decision**: Keep all components inside the existing modular monolith. Do NOT introduce Kafka, RabbitMQ, Redis, external distributed locks, or separate microservices.
- **Rationale**: PostgreSQL provides atomic row locks (`FOR UPDATE SKIP LOCKED`), MVCC isolation, transactions, and vector indexing within a single deployment unit, avoiding unnecessary distributed operational complexity.

---

### Decision 2: PostgreSQL as Durable Queue & Coordination Layer
- **Context**: Asynchronous ingestion needs durable persistence across application restarts.
- **Decision**: Persist document states directly in the `documents` table using `status`, `processing_version`, `lease_until`, `retry_count`, and `next_attempt_at`. Claim jobs via `DocumentJobClaimer`.
- **Rationale**: Do not rely on Spring `@Async` as a job queue. PostgreSQL provides durable FIFO ordering, zero message loss on restarts, and atomic concurrency safety.

---

### Decision 3: Canonical 768-Dim Gemini Embedding (`gemini-embedding-2`)
- **Context**: Need high-quality semantic retrieval while operating under free-tier / API quotas (100 RPM, 30,000 TPM).
- **Decision**: Retain Google's `gemini-embedding-2` configured with `outputDimensionality = 768`. Do not switch to local `multilingual-e5-small` or local ONNX models merely to avoid API quotas.
- **Rationale**: The 768-dim representation provides superior cross-lingual accuracy and semantic matching for software artifacts and technical documentation. Quota constraints are safely managed through admission control rather than degrading model quality.

---

### Decision 4: Staging Table Separation (`document_chunk_staging`)
- **Context**: Ingestion of large documents can be interrupted by network timeouts or API quotas (429).
- **Decision**: Separate active work in progress (`document_chunk_staging`) from the queryable RAG index (`document_chunks`).
- **Rationale**: The published index must never contain partial or corrupted generations. By persisting chunk text into staging before invoking embedding APIs, resume operations do not need to re-download files from S3 or re-parse them with Apache Tika.

---

### Decision 5: `embedding IS NULL` as Source of Truth for Resumability
- **Context**: Tracking completion of chunks in multi-batch ingestion.
- **Decision**: Query `WHERE document_id = ? AND processing_version = ? AND embedding IS NULL` to identify remaining work.
- **Rationale**: Do not use `last_chunk_index` as a coarse cursor. Explicit null checks at the database row level guarantee fine-grained, batch-level idempotence and survive arbitrary worker interruptions without duplicate API calls.

---

### Decision 6: Processing Version Fencing Against Zombie Workers
- **Context**: If a worker node encounters a long garbage collection pause or network lag, its lease may expire and another worker may claim the document.
- **Decision**: Each claim increments `processing_version`. All database updates (failure, retry, and publication) require `processing_version = claimedVersion`.
- **Rationale**: Fencing guarantees that an expired or zombie worker cannot overwrite newer work or corrupt state transitions.

---

### Decision 7: Atomic Publication with Row-Lock Verification
- **Context**: Publishing completed embeddings into the queryable `document_chunks` table.
- **Decision**: Wrap publication in a single database transaction holding `SELECT ... FOR UPDATE` on `documents`, verify version and status, delete old chunks, copy staged chunks to published table via `INSERT INTO ... SELECT`, and update document status to `READY`.
- **Rationale**: Readers querying RAG never observe partially ingested documents. Stale workers are rejected atomically.

---

### Decision 8: Distinction Between Normal Pacing and `RETRY_WAIT`
- **Context**: Quota admission under 100 RPM and 30,000 TPM.
- **Decision**:
  - **Normal Pacing**: When the local sliding window limit is temporarily saturated, the worker waits up to `pacingWaitMs` (default: 5000ms) on a `Condition.await()`. The document remains in `PROCESSING` status.
  - **RETRY_WAIT**: Transitioned only when the external provider actually returns HTTP 429, quota exhaustion, network timeouts, or when pacing times out. The worker thread is released immediately and `next_attempt_at` is scheduled with exponential backoff.
- **Rationale**: Avoids state-machine thrashing and excessive database updates during normal ingestion rate limiting, while preventing worker threads from blocking during multi-minute backoffs.

---

### Decision 9: Dual-Dimension Admission Limiter (RPM + TPM) with Protected Headroom
- **Context**: Gemini enforces both 100 RPM and 30,000 TPM.
- **Decision**: Implement a sliding 60-second window in `RpmRateLimiter` that records `AdmissionRecord(timestamp, tokens)`. Heuristically estimate tokens (`chars / 3`). Reserve dedicated headroom (`interactiveHeadroom`, `interactiveTpmHeadroom`) for user-facing chat queries.
- **Rationale**: Concurrency semaphores or fixed sleeps alone do not guard against token burst limits. Reserving headroom guarantees interactive search is never starved by background document ingestion.

---

### Decision 10: Provider SDK Retries Disabled (`maxRetries = 0`)
- **Context**: LangChain4j `GoogleAiEmbeddingModel` defaults to internal retries with fixed delays.
- **Decision**: Explicitly configure `GoogleAiEmbeddingModel.builder().maxRetries(0)`.
- **Rationale**: Silent SDK-level retries undermine application-level quota admission and can cause rapid quota exhaustion. All retries must be governed by TaskPilot's state machine.

---

### Decision 11: Preserve HNSW Index without Drop/Recreate
- **Context**: Batch inserting vectors into `document_chunks`.
- **Decision**: Keep the existing PostgreSQL HNSW index on `document_chunks` intact. Do NOT drop and rebuild the index per document publication.
- **Rationale**: Incremental HNSW insertions in PostgreSQL pgvector are fast and preserve continuous read availability for concurrently executing user queries.
