-- Migration V28: Migrate identity columns to dedicated PostgreSQL sequences
-- Enables JDBC batch insert capabilities in Hibernate/JPA while preserving existing primary keys

DO $$
DECLARE
    tbl TEXT;
    seq TEXT;
    max_id BIGINT;
    tbl_list TEXT[] := ARRAY[
        'users',
        'skills',
        'projects',
        'sprints',
        'tasks',
        'labels',
        'comments',
        'notifications',
        'chat_sessions',
        'chat_messages',
        'ai_logs',
        'ai_chat_requests',
        'refresh_tokens',
        'password_reset_tokens'
    ];
BEGIN
    FOREACH tbl IN ARRAY tbl_list LOOP
        IF to_regclass('public.' || tbl) IS NOT NULL THEN
            seq := tbl || '_id_seq';

            -- 1. Drop identity constraint if column was generated as identity
            -- Note: in PostgreSQL, dropping identity drops the auto-generated identity sequence if one existed.
            EXECUTE format('ALTER TABLE public.%I ALTER COLUMN id DROP IDENTITY IF EXISTS', tbl);

            -- 2. Create explicit sequence if not exists
            EXECUTE format('CREATE SEQUENCE IF NOT EXISTS public.%I', seq);

            -- 3. Synchronize current sequence value with existing table data
            EXECUTE format('SELECT MAX(id) FROM public.%I', tbl) INTO max_id;
            IF max_id IS NULL THEN
                EXECUTE format('SELECT setval(%L, 1, false)', 'public.' || seq);
            ELSE
                EXECUTE format('SELECT setval(%L, %s, true)', 'public.' || seq, max_id);
            END IF;

            -- 4. Set sequence as column default
            EXECUTE format('ALTER TABLE public.%I ALTER COLUMN id SET DEFAULT nextval(%L)', tbl, 'public.' || seq);

            -- 5. Establish sequence ownership
            EXECUTE format('ALTER SEQUENCE public.%I OWNED BY public.%I.id', seq, tbl);
        END IF;
    END LOOP;
END $$;

