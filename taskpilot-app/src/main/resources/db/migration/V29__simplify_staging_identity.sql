-- V29: Simplify staging uniqueness model from (document_id, processing_version, chunk_index)
-- to (document_id, chunk_index). Preserves already-computed embeddings across versions.

-- 1. Reconcile possible duplicate (document_id, chunk_index) rows before adding constraint:
--    Rule: Prefer row where embedding IS NOT NULL; if equal embedding state, keep larger id.
DELETE FROM document_chunk_staging a
USING document_chunk_staging b
WHERE a.document_id = b.document_id
  AND a.chunk_index = b.chunk_index
  AND (
      (a.embedding IS NULL AND b.embedding IS NOT NULL)
      OR (
          (a.embedding IS NULL) = (b.embedding IS NULL)
          AND a.id < b.id
      )
  );

-- 2. Drop old constraints and indexes
ALTER TABLE document_chunk_staging DROP CONSTRAINT IF EXISTS uq_document_chunk_staging;
ALTER TABLE document_chunk_staging DROP CONSTRAINT IF EXISTS uq_staging_doc_version_chunk;

DROP INDEX IF EXISTS idx_staging_unembedded;
DROP INDEX IF EXISTS idx_staging_doc_version;

-- 3. Add new unique constraint on (document_id, chunk_index)
ALTER TABLE document_chunk_staging
ADD CONSTRAINT uq_staging_doc_chunk
UNIQUE (document_id, chunk_index);

-- 4. Create partial index for pending (unembedded) chunks
CREATE INDEX IF NOT EXISTS idx_staging_unembedded
ON document_chunk_staging (document_id, chunk_index)
WHERE embedding IS NULL;

-- 5. Index for foreign key lookup and document-level cleanup
CREATE INDEX IF NOT EXISTS idx_staging_doc_id
ON document_chunk_staging (document_id);
