ALTER TABLE "project_meetings"
    ADD COLUMN IF NOT EXISTS "scheduled_start_time" TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS "scheduled_end_time" TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS "idx_project_meetings_scheduled_start" ON "project_meetings"("scheduled_start_time");
