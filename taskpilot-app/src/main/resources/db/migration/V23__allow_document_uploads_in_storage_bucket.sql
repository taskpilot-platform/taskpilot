-- Update Supabase storage.buckets configuration to allow knowledge document uploads (PDF, DOCX, TXT, MD, etc.)
-- and expand file size limit from 1MB to 50MB.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = 'storage' AND table_name = 'buckets'
    ) THEN
        UPDATE storage.buckets
        SET allowed_mime_types = NULL,
            file_size_limit = 52428800
        WHERE id = 'avatars';
    END IF;
END $$;
