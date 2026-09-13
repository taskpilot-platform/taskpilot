-- V30: Remove processing_version from document_chunk_staging
-- Fencing is authoritative on documents.processing_version; staging identity is strictly (document_id, chunk_index)

ALTER TABLE document_chunk_staging
DROP COLUMN IF EXISTS processing_version;
