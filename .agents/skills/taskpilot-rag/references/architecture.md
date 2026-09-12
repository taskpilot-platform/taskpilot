# TaskPilot RAG Architecture Reference

## 1. System Topology & Subsystem Boundaries

TaskPilot is a **modular monolith** with clean module separation (`taskpilot-infrastructure`, `taskpilot-contracts`, `taskpilot-users`, `taskpilot-projects`, `taskpilot-ai`, `taskpilot-app`).

```text
[ Client / Web UI ] (React, Vite, TypeScript)
       │
       ├──► 1. POST /api/projects/{id}/documents (Upload multipart file)
       │       Returns HTTP 201 with status: "QUEUED"
       │
       └──► 2. Polling loop (every 2.5s while non-terminal: QUEUED, PROCESSING, RETRY_WAIT)
               Stops when terminal: READY or FAILED

┌─────────────────────────────────────────────────────────────────────────────┐
│                             TaskPilot Backend                               │
├─────────────────────────────────────────────────────────────────────────────┤
│  [ Durable Queue & Scheduling ]                                             │
│  DocumentJobPoller (Spring @Scheduled fixedDelayString = 2000ms)            │
│         │                                                                   │
│         ▼                                                                   │
│  DocumentJobClaimer                                                         │
│         │ Executes atomic claim SQL:                                        │
│         │   SELECT id, project_id, processing_version, retry_count          │
│         │   FROM documents                                                  │
│         │   WHERE (status = 'QUEUED')                                       │
│         │      OR (status = 'RETRY_WAIT' AND next_attempt_at <= NOW())      │
│         │      OR (status = 'PROCESSING' AND lease_until <= NOW())          │
│         │   ORDER BY created_at ASC                                         │
│         │   FOR UPDATE SKIP LOCKED LIMIT 1;                                 │
│         │ Increments processing_version, sets status='PROCESSING',          │
│         │ establishes lease_until = NOW() + leaseDurationSeconds.           │
│                                                                             │
│  [ Resumable Ingestion Pipeline ]                                           │
│  DocumentIngestionService (DocumentIngestionServiceImpl)                    │
│         │ 1. Check Staging: document_chunk_staging has rows for version?    │
│         │    IF NOT:                                                        │
│         │      - Download file from StorageService (S3StorageServiceImpl)   │
│         │      - Extract text using Apache Tika (DocumentTextExtractor)     │
│         │      - Chunk text deterministically using DocumentChunker         │
│         │      - Insert all text chunks into document_chunk_staging (nulls) │
│         │    IF YES:                                                        │
│         │      - Reuse existing staged text chunks (skip S3/Tika/chunking)  │
│         │                                                                   │
│         │ 2. Resumable Embedding Loop:                                      │
│         │    SELECT * FROM document_chunk_staging                           │
│         │    WHERE document_id = ? AND processing_version = ?               │
│         │      AND embedding IS NULL                                        │
│         │    ORDER BY chunk_index ASC;                                      │
│         │    Partition into batches of size <= maxBatchSize (default: 100)  │
│         │    For each batch:                                                │
│         │      - EmbeddingGateway.embedForIngestion(batchTexts)             │
│         │      - UPDATE document_chunk_staging SET embedding = ?            │
│         │                                                                   │
│         │ 3. Atomic Publication with Fencing:                               │
│         │    BEGIN TRANSACTION                                              │
│         │      SELECT processing_version FROM documents                     │
│         │      WHERE id = ? AND status = 'PROCESSING' FOR UPDATE;           │
│         │      Verify processing_version == claimedVersion                  │
│         │      DELETE FROM document_chunks WHERE document_id = ?;           │
│         │      INSERT INTO document_chunks SELECT FROM staging;             │
│         │      UPDATE documents SET status='READY', retry_count=0...        │
│         │    COMMIT                                                         │
│         │                                                                   │
│         │ 4. Post-Publication Cleanup:                                      │
│         │    DELETE FROM document_chunk_staging WHERE document_id = ?       │
│         │                                                                   │
│  [ Quota Admission & Rate Limiting ]                                        │
│  EmbeddingGateway                                                           │
│         │ embedForSearch(query)  /  embedForIngestion(texts)                │
│         ▼                                                                   │
│  RpmRateLimiter (Dual Dimension: RPM + TPM, Sliding 60-Second Window)       │
│         ├── Tracks AdmissionRecord(timestamp, tokens)                       │
│         ├── Global Limit: maxRpm (100), maxTpm (30,000)                     │
│         ├── Interactive Reservation: interactiveHeadroom (10), TpmHeadroom  │
│         ├── Background Capacity: maxRpm - Headroom, maxTpm - TpmHeadroom    │
│         └── Normal Pacing: acquireBackgroundWithPacing(tokens, maxWaitMs)   │
│             Awaits capacity on condition variable instead of busy sleep     │
│                                                                             │
│  [ Canonical Embedding Provider ]                                           │
│  GoogleAiEmbeddingServiceImpl (GoogleAiEmbeddingModel)                      │
│         └── model: gemini-embedding-2, dimension: 768, maxRetries: 0       │
│                                                                             │
│  [ Retrieval & Search Flow ]                                                │
│  TaskPilotAiTools (searchProjectKnowledge)                                  │
│         │                                                                   │
│         ▼                                                                   │
│  ProjectKnowledgeService                                                    │
│         ├── Validate user is active project member (403 Forbidden before API)│
│         ├── EmbeddingGateway.embedForSearch(query)                          │
│         └── DocumentChunkRepository.findNearestChunks(projectId, vector, k) │
│             PostgreSQL HNSW cosine distance (<=>) query                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Document State Machine

```text
       [Upload]
          │
          ▼
       QUEUED ───────────────┐
          │ (Claim)          │
          ▼                  │ (Expired Lease)
     PROCESSING ◄────────────┤
       │     │               │
(429 / │     │ (Success)     │
Error) │     ▼               │
       │   READY             │
       │                     │
       ▼                     │
  RETRY_WAIT ────────────────┘
   (Eligible when NOW() >= next_attempt_at)
       │
       │ (Exhausted maxRetryAttempts or Permanent Error)
       ▼
     FAILED
```

- **UPLOADING**: Transient state during initial file streaming to S3.
- **QUEUED**: Durable state committed in PostgreSQL upon successful S3 upload.
- **PROCESSING**: Atomically claimed by a worker with an incremented `processing_version` and `lease_until`.
- **RETRY_WAIT**: Transitioned on transient errors (429, timeouts) with `next_attempt_at` and backoff. Releases worker thread immediately.
- **READY**: Reached only after 100% of chunks in staging have embeddings and atomic publication commits.
- **FAILED**: Permanent failures (e.g. unparseable/empty text) or retry exhaustion (`retry_count >= maxRetryAttempts`).

---

## 3. Durable Job Queue & Fencing Model

### 3.1 Partial Queue Index
```sql
CREATE INDEX idx_documents_queue_claim
ON documents (status, next_attempt_at, lease_until, created_at ASC)
WHERE status IN ('QUEUED', 'RETRY_WAIT', 'PROCESSING');
```

### 3.2 Atomic Job Claiming
Executed by `DocumentJobClaimer.claimNextJob`:
```sql
SELECT id, project_id, processing_version, retry_count
FROM documents
WHERE (status = 'QUEUED')
   OR (status = 'RETRY_WAIT' AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()))
   OR (status = 'PROCESSING' AND lease_until <= NOW())
ORDER BY created_at ASC
FOR UPDATE SKIP LOCKED
LIMIT 1;

UPDATE documents
SET status = 'PROCESSING',
    processing_version = processing_version + 1,
    lease_until = NOW() + (leaseSeconds || ' seconds')::interval,
    next_attempt_at = NULL,
    error_message = NULL,
    updated_at = NOW()
WHERE id = ?;
```

### 3.3 Zombie Worker Fencing
Every processing cycle operates with a fixed `claimedVersion`.
- **On Failure / Retry**: `UPDATE documents ... WHERE id = ? AND processing_version = claimedVersion AND status = 'PROCESSING'`. If 0 rows are updated, the worker knows its claim expired and discards its state.
- **On Publication**: A transaction acquires `SELECT FOR UPDATE` on `documents`, verifies `processing_version == claimedVersion`, copies staging to published, and updates status to `READY`. A stale worker cannot commit or overwrite newer work.

---

## 4. Staging & Resumable Ingestion Architecture

### 4.1 Schema (`document_chunk_staging`)
```sql
CREATE TABLE document_chunk_staging (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    processing_version INT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(768) NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_document_chunk_staging UNIQUE (document_id, processing_version, chunk_index)
);

CREATE INDEX idx_staging_unembedded
ON document_chunk_staging (document_id, processing_version, chunk_index)
WHERE embedding IS NULL;
```

### 4.2 Resumable Workflow
1. **Upfront Text Staging**: All text chunks are saved before the first embedding API call.
2. **Resume Query**: Resuming workers execute `SELECT ... WHERE document_id = ? AND processing_version = ? AND embedding IS NULL`.
3. **Chunk Preservation**: Already-computed vector embeddings in staging rows are never re-requested or discarded.
4. **Independent Generations**: If a new worker claims `version = 2`, it operates in its own staging space, completely isolated from `version = 1`.

---

## 5. Quota Admission & Embedding Gateway

### 5.1 Dual-Dimension Limiter (`RpmRateLimiter`)
- **Sliding Window**: 60 seconds (`60,000ms`), tracking `AdmissionRecord(long timestamp, int tokens)`.
- **Token Estimation**: Heuristic estimator (~1 token per 3 characters) protects against 30,000 TPM limit.
- **Capacity Reservation**:
  - `backgroundRpmLimit = maxRpm - interactiveHeadroom`
  - `backgroundTpmLimit = maxTpm - interactiveTpmHeadroom`
- **Normal Pacing**: `acquireBackgroundWithPacing(estimatedTokens, pacingWaitMs)` uses Java `ReentrantLock` and `Condition.await()`. Worker sleeps only until the oldest record in the sliding window expires or `pacingWaitMs` elapses, preventing busy looping and eliminating artificial `RETRY_WAIT` transitions.

### 5.2 Provider Retry Decoupling
`GoogleAiEmbeddingModel` is configured with `maxRetries = 0`. LangChain4j internal retries are disabled, ensuring all retry, backoff, and admission decisions are centrally governed by TaskPilot.

---

## 6. Atomic Publication & Clean-Up

### 6.1 Publication Transaction
```java
transactionTemplate.execute(status -> {
    // 1. Row lock and version check
    List<Integer> versions = jdbcTemplate.query(
        "SELECT processing_version FROM documents WHERE id = ? AND status = 'PROCESSING' FOR UPDATE",
        ps -> ps.setLong(1, documentId),
        (rs, rowNum) -> rs.getInt("processing_version")
    );
    if (versions.isEmpty() || versions.get(0) != claimedVersion) return false;

    // 2. Replace published chunks atomically
    documentChunkRepository.deleteByDocumentId(documentId);
    stagingRepository.copyStagedToPublished(documentId, claimedVersion, projectId);

    // 3. Mark READY and reset retry fields
    jdbcTemplate.update("""
        UPDATE documents
        SET status = 'READY', retry_count = 0, next_attempt_at = NULL,
            lease_until = NULL, error_message = NULL, updated_at = NOW()
        WHERE id = ? AND processing_version = ? AND status = 'PROCESSING'
        """, documentId, claimedVersion);

    return true;
});
```

### 6.2 Staging Cleanup
Performed after the publication transaction commits. Non-fatal cleanup failures are logged and do not impact publication correctness or cause rollbacks.

---

## 7. Frontend Integration & Polling

- Frontend hook `useProjectDocuments(projectId)` inspects document status.
- If any document is in a non-terminal state (`QUEUED`, `PROCESSING`, `RETRY_WAIT`, `UPLOADING`), it polls every 2500ms using a background fetch.
- Polling automatically ceases once all documents reach a terminal state (`READY`, `FAILED`).
- `DocumentStatusBadge` renders dedicated UI badges with localized status labels for all states.
