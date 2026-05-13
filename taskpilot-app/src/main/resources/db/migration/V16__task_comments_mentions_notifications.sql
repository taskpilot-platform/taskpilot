ALTER TABLE "comments"
    ADD COLUMN IF NOT EXISTS "updated_at" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS "comment_mentions"
(
    "comment_id" BIGINT NOT NULL,
    "user_id"    BIGINT NOT NULL,
    "created_at" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("comment_id", "user_id"),
    FOREIGN KEY ("comment_id") REFERENCES "comments" ("id") ON DELETE CASCADE,
    FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS "idx_comments_task_created_at"
    ON "comments" ("task_id", "created_at");

CREATE INDEX IF NOT EXISTS "idx_comments_user_id"
    ON "comments" ("user_id");

CREATE INDEX IF NOT EXISTS "idx_comment_mentions_user_id"
    ON "comment_mentions" ("user_id");

ALTER TYPE "notification_type" ADD VALUE IF NOT EXISTS 'COMMENT';
ALTER TYPE "notification_type" ADD VALUE IF NOT EXISTS 'MENTION';

ALTER TABLE public.comment_mentions ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF to_regrole('anon') IS NOT NULL AND to_regrole('authenticated') IS NOT NULL THEN
        DROP POLICY IF EXISTS p_comment_mentions_deny_client ON public.comment_mentions;
        CREATE POLICY p_comment_mentions_deny_client
            ON public.comment_mentions
            FOR ALL
            TO anon, authenticated
            USING (false)
            WITH CHECK (false);
    END IF;

    IF to_regrole('service_role') IS NOT NULL THEN
        DROP POLICY IF EXISTS p_comment_mentions_service_role ON public.comment_mentions;
        CREATE POLICY p_comment_mentions_service_role
            ON public.comment_mentions
            FOR ALL
            TO service_role
            USING (true)
            WITH CHECK (true);
    END IF;
END $$;
