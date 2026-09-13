-- Create dedicated private storage bucket for RAG documents (25MB limit)
-- and preserve avatars bucket for profile images (1MB limit).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = 'storage' AND table_name = 'buckets'
    ) THEN
        -- 1. Create or update 'documents' bucket: private, 25MB limit, all document MIME types
        INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
        VALUES ('documents', 'documents', false, 26214400, NULL)
        ON CONFLICT (id) DO UPDATE
        SET public = false,
            file_size_limit = 26214400,
            allowed_mime_types = NULL;

        -- 2. Preserve 'avatars' bucket: public, 1MB limit, images only
        UPDATE storage.buckets
        SET public = true,
            file_size_limit = 1048576,
            allowed_mime_types = ARRAY['image/*']
        WHERE id = 'avatars';
    END IF;
END $$;
