-- V26: Document ingestion queue and concurrency fencing support
ALTER TABLE documents ADD COLUMN IF NOT EXISTS processing_version INT NOT NULL DEFAULT 0;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP WITH TIME ZONE;

-- Partial index optimized for atomic queue polling & job claiming
DROP INDEX IF EXISTS idx_documents_queue_claim;

CREATE INDEX IF NOT EXISTS idx_documents_queue_claim
ON documents (status, next_attempt_at, lease_until, created_at ASC)
WHERE status IN ('QUEUED', 'RETRY_WAIT', 'PROCESSING');
