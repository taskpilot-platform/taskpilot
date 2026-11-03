ALTER TABLE "project_meetings"
    ADD COLUMN IF NOT EXISTS "notes" TEXT,
    ADD COLUMN IF NOT EXISTS "transcript" TEXT,
    ADD COLUMN IF NOT EXISTS "ai_summary" TEXT,
    ADD COLUMN IF NOT EXISTS "ai_summary_generated_at" TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS "action_items_json" TEXT;

CREATE INDEX IF NOT EXISTS "idx_project_meetings_ai_summary_generated" ON "project_meetings"("ai_summary_generated_at");
