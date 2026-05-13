ALTER TABLE "comments"
    ADD COLUMN IF NOT EXISTS "parent_comment_id" BIGINT,
    ADD COLUMN IF NOT EXISTS "deleted_at" TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS "deleted_by" BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_comments_parent_comment'
    ) THEN
        ALTER TABLE "comments"
            ADD CONSTRAINT "fk_comments_parent_comment"
            FOREIGN KEY ("parent_comment_id") REFERENCES "comments" ("id") ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_comments_deleted_by'
    ) THEN
        ALTER TABLE "comments"
            ADD CONSTRAINT "fk_comments_deleted_by"
            FOREIGN KEY ("deleted_by") REFERENCES "users" ("id") ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS "idx_comments_task_parent_created_at"
    ON "comments" ("task_id", "parent_comment_id", "created_at");

ALTER TYPE "notification_type" ADD VALUE IF NOT EXISTS 'REPLY';
