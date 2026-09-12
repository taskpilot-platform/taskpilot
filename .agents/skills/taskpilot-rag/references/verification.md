# TaskPilot RAG Verification Protocol Reference

## 1. Verification Principles
- **Principle 1**: Every verification claim must be backed by an executed command and logged test output. Never claim a test passed without execution.
- **Principle 2**: Tests must verify real invariants (database fencing, state transitions, admission rates, resumability) rather than superficial mocks.
- **Principle 3**: Regression runs must verify all 7 modules of the TaskPilot modular monolith.

---

## 2. Invariant Verification Mapping

| Invariant | Test Suite / Class | Method Name | Verification Details |
|---|---|---|---|
| **Concurrent Job Claiming** | `DocumentJobConcurrencyIntegrationTest` | `testConcurrentClaimSingleJob` | 3 threads compete for 1 QUEUED doc; exactly 1 wins (`SKIP LOCKED`), sets `PROCESSING`, version=1 |
| **Disjoint Job Distribution** | `DocumentJobConcurrencyIntegrationTest` | `testMultipleJobsConcurrentClaim` | 3 workers claim 3 queued docs concurrently; each receives disjoint doc ID without collision |
| **Zombie Finalize Rejection** | `DocumentJobConcurrencyIntegrationTest` | `testZombieFinalizeRejection` | Worker A (v1) lease expires; Worker B claims (v2) & marks READY; Stale Worker A publication rejected |
| **Zombie Failure Rejection** | `DocumentJobConcurrencyIntegrationTest` | `testZombieFailureRejection` | Stale worker attempt to mark document FAILED/RETRY_WAIT with old version affects 0 rows |
| **Expired Lease Recovery** | `DocumentJobConcurrencyIntegrationTest` | `testExpiredLeaseReclaimed` | Document stuck in PROCESSING with expired `lease_until` is re-claimed with incremented version |
| **Retry Wait Respects Time** | `DocumentJobConcurrencyIntegrationTest` | `testRetryWaitNotClaimedEarly` | RETRY_WAIT cannot be claimed before `next_attempt_at`, becomes claimable immediately after |
| **Resume Pending Chunks** | `DocumentIngestionServiceImplTest` | `testResumeIngestionFromPendingChunksWithoutReparsing` | With staged chunks present, S3 download and Tika parsing skipped; only `embedding IS NULL` embedded |
| **Text Staging Upfront** | `DocumentIngestionServiceImplTest` | `testIngestDocumentSuccess` | All text chunks inserted into `document_chunk_staging` prior to calling embedding gateway |
| **Atomic Publication** | `DocumentIngestionServiceImplTest` | `testIngestDocumentSuccess` | Old chunks deleted and staged chunks copied to `document_chunks` within transaction, doc marked READY |
| **Provider 429 to RETRY_WAIT** | `DocumentIngestionRetryStateTest` | `testProvider429TriggersRetryWait` | 429 response transitions status to `RETRY_WAIT`, sets `next_attempt_at`, releases worker (<2s) |
| **Retry Limit to FAILED** | `DocumentIngestionRetryStateTest` | `testRetryLimitFailsDocument` | Document with `retry_count >= 5` transitions to `FAILED` with error message |
| **Non-Retryable Error Fails** | `DocumentIngestionRetryStateTest` | `testNonRetryableErrorMarksFailed` | Empty/corrupt document text immediately transitions to `FAILED` without retrying |
| **State Hygiene on READY** | `DocumentIngestionRetryStateTest` | `testSuccessfulStateHygiene` | Successful publication resets `retry_count=0`, `next_attempt_at=NULL`, `lease_until=NULL` |
| **Batch Splitting (<= 100)** | `EmbeddingGatewayAndBatchTest` | `testEmbeddingBatchLimitEnforcement` | 235 items split into provider batches of 100, 100, 35 |
| **SDK Retries Disabled** | `EmbeddingGatewayAndBatchTest` | `testSdkRetriesDisabled` | Reflection verifies `GoogleAiEmbeddingModel.maxRetries == 0` |
| **Shared RPM Admission** | `EmbeddingGatewayAndBatchTest` | `testSharedRpmQuotaAccounting` | Background capped at `maxRpm - headroom`; interactive search admitted into reserved headroom |
| **Pacing & Quota Rejection** | `EmbeddingGatewayAndBatchTest` | `testGatewayQuotaExceededBehavior` | Exhausted capacity triggers pacing timeout and throws `QuotaExceededException` for retry |
| **Frontend Polling Lifecycle** | `useProjectDocuments.test.tsx` (Vitest) | `polls repeatedly when document is QUEUED...` | Hook polls every 2500ms during QUEUED/PROCESSING/RETRY_WAIT; stops when READY or FAILED |
| **End-to-End Pipeline & Auth** | `RagEndToEndIntegrationTest` | `testSearchProjectKnowledgeUnauthorizedThrowsForbidden` | Verifies 403 Forbidden before vector search or query embedding occurs |
| **Database Migration Integrity** | `FlywayMigrationVerificationTest` | `testFlywayMigrationV22` | Verifies all 27 migrations applied cleanly to live PostgreSQL schema |

---

## 3. Standard Verification Commands

```powershell
# 1. Compile all modules without external network dependencies
.\mvnw.cmd test-compile -o

# 2. Run focused RAG ingestion & rate limiter tests
.\mvnw.cmd test -pl taskpilot-ai "-Dtest=RpmRateLimiterTest,DocumentIngestionServiceImplTest,DocumentIngestionRetryStateTest,EmbeddingGatewayAndBatchTest,DocumentJobPollerAndEndToEndTest,RagEndToEndIntegrationTest" -o

# 3. Run full backend regression across all 7 modules
.\mvnw.cmd test -o

# 4. Run frontend tests (Vitest)
cd ..\taskpilot-frontend
npm test

# 5. Run frontend production build & TypeScript validation
npm run build
```
