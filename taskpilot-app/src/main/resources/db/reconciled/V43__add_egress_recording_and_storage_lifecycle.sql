SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE public.project_meetings
    ADD COLUMN IF NOT EXISTS egress_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS recording_status VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    ADD COLUMN IF NOT EXISTS recording_error TEXT,
    ADD COLUMN IF NOT EXISTS backup_storage_used BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_project_meetings_egress_id
    ON public.project_meetings(egress_id);

CREATE INDEX IF NOT EXISTS idx_project_meetings_recording_status
    ON public.project_meetings(recording_status);

ALTER TABLE public.project_files
    ADD COLUMN IF NOT EXISTS storage_provider VARCHAR(32) NOT NULL DEFAULT 's3',
    ADD COLUMN IF NOT EXISTS source_egress_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS archive_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS archive_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS archive_error TEXT,
    ADD COLUMN IF NOT EXISTS content_md5 VARCHAR(64);

UPDATE public.project_files
SET storage_provider = 'gdrive'
WHERE lower(storage_bucket) = 'googledrive'
  AND storage_provider = 's3';

CREATE UNIQUE INDEX IF NOT EXISTS uq_project_files_source_egress_id
    ON public.project_files(source_egress_id)
    WHERE source_egress_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_project_files_archive_status
    ON public.project_files(archive_status);
